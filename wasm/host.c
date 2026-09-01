/*
 * Bridge between the WebAssembly sandbox and the kernel.
 *
 * The guest is Sokoban, written in Rust and compiled to wasm32. It never sees
 * a kernel address: it asks how big its window is, draws in its own
 * coordinates from 0,0, and this layer translates and clamps. Three
 * independent things must fail before it can touch anything it should not:
 *
 *   1. the interpreter bounds checks every linear memory access against the
 *      single page the module declared,
 *   2. these host functions clamp drawing to the window rectangle,
 *   3. the graphics clip rectangle, set by the desktop before the guest runs,
 *      is enforced down in the framebuffer routines.
 *
 * Input goes the other way through a small ring buffer: the desktop pushes
 * keys in, the guest drains them at the start of its frame.
 */
#include "wasm.h"
#include "guest_module.h"
#include "images.h"

/* Kernel entry points, cdecl, from boot/sys_api.asm and boot/font.asm. */
extern void sys_set_color(int rgb);
extern void sys_fill_rect(int x, int y, int w, int h);
extern void draw_char_vram(int ch, int x, int y, int color);
extern void sys_beep(int hz);
extern void sys_sleep(int ms);
extern int sys_get_ticks(void);

/* wasm/sb16.c */
int sb16_init(void);
int sb16_present(void);
int sb16_effect(int id);

/* clip ids, matching the enum in sb16.c */
#define SND_STEP 0
#define SND_PUSH 1
#define SND_BUMP 2
#define SND_WIN  3

static int g_audio_ready;

/* Framebuffer state, for blitting pictures without a syscall per pixel. */
extern unsigned char *vram_back_buffer;
extern int g_pitch;
extern int clip_x, clip_y, clip_x2, clip_y2;

#define KEY_SLOTS 16

static wasm_module g_mod;
static unsigned char g_mem[65536];      /* one page: all the guest may address */
static int g_loaded;
static int g_failed;                    /* remembers a load failure, do not retry every frame */

static int g_ox, g_oy, g_ow, g_oh;      /* window origin and size */
static int g_color = 0x00FFFFFF;

static int g_keys[KEY_SLOTS];
static int g_key_head, g_key_tail;

/*
 * Music.
 *
 * The melodies are the ones from the original game's sound.rs, which stores
 * them as notes rather than as samples, so they can be played on the PC
 * speaker unchanged.
 *
 * The sequencer lives here rather than in the guest because it has to be
 * advanced roughly every millisecond, and running the interpreter that often
 * would cost far more than the music. The guest only says which track to play;
 * the desktop's idle loop ticks this, and each tick is a comparison against the
 * system clock. Nothing sleeps, so nothing blocks the machine.
 */
#define MUSIC_GAP 40

static const short fanfare_hz[]  = { 392, 494, 587, 784, 698, 659 };
static const short fanfare_ms[]  = { 250, 250, 250, 250, 250, 250 };

static const short theme_hz[]    = { 262, 330, 392, 440, 349, 294, 262, 330, 392, 523, 392 };
static const short theme_ms[]    = { 150, 150, 200, 150, 150, 200, 150, 200, 250, 200, 300 };

static int g_track;              /* 0 none, 1 fanfare once, 2 theme looped */
static int g_note, g_resting, g_note_at, g_sounding;
static int g_sound_on = 1;

static void music_silence(void)
{
    if (g_sounding) {
        sys_beep(0);
        g_sounding = 0;
    }
}

static void music_select(int track)
{
    music_silence();
    g_track = track;
    g_note = 0;
    g_resting = 0;
    g_note_at = sys_get_ticks();
}

static int hf_music(int *a, int n, void *user)
{
    (void)user;
    if (n < 1) return 0;

    if (!g_audio_ready) {
        g_audio_ready = 1;
        sb16_init();
    }

    /* the level fanfare is a sampled clip when the card is there */
    if (a[0] == 1 && sb16_present() && sb16_effect(SND_WIN)) return 0;

    music_select(a[0]);
    return 0;
}

static int hf_set_color(int *a, int n, void *user)
{
    (void)user;
    if (n >= 1) {
        g_color = a[0] & 0x00FFFFFF;
        sys_set_color(g_color);
    }
    return 0;
}

static int hf_fill_rect(int *a, int n, void *user)
{
    int x, y, w, h;
    (void)user;
    if (n < 4) return 0;
    x = a[0]; y = a[1]; w = a[2]; h = a[3];
    if (w <= 0 || h <= 0) return 0;

    /* Clamp into the window before translating, so a guest asking for a huge
       or negative rectangle simply draws less. */
    if (x < 0) { w += x; x = 0; }
    if (y < 0) { h += y; y = 0; }
    if (x >= g_ow || y >= g_oh) return 0;
    if (w > g_ow - x) w = g_ow - x;
    if (h > g_oh - y) h = g_oh - y;
    if (w <= 0 || h <= 0) return 0;

    sys_fill_rect(g_ox + x, g_oy + y, w, h);
    return 0;
}

/* The guest has no font, so numbers come back here to be rendered. */
static int hf_draw_int(int *a, int n, void *user)
{
    int value, x, y, i, len;
    unsigned u;
    char digits[12];
    (void)user;
    if (n < 3) return 0;
    value = a[0]; x = a[1]; y = a[2];

    if (x < 0 || y < 0 || x >= g_ow || y + 16 > g_oh) return 0;

    len = 0;
    if (value < 0) {
        u = (unsigned)(-value);
    } else {
        u = (unsigned)value;
    }
    do {
        digits[len++] = (char)('0' + (u % 10u));
        u /= 10u;
    } while (u && len < 11);
    if (value < 0 && len < 12) digits[len++] = '-';

    for (i = 0; i < len; i++) {
        int px = x + (len - 1 - i) * 8;
        if (px + 8 > g_ow) continue;
        draw_char_vram((int)(unsigned char)digits[i], g_ox + px, g_oy + y, g_color);
    }
    return 0;
}

/*
 * Draws one of the pictures the kernel carries. The guest names a picture by
 * index and never touches the pixels, the same way a program asks the system
 * for a font rather than being handed the glyph memory.
 *
 * Going through fill_rect would mean 25000 calls across the sandbox boundary
 * for a single image, so the copy happens here, straight into the back buffer,
 * clipped to both the window and the active clip rectangle.
 */
static int hf_draw_image(int *a, int n, void *user)
{
    const image_asset *im;
    unsigned char *base;
    int index, dx, dy, scale, sy, ky, sx, kx;
    int x_lo, x_hi, y_lo, y_hi, py, px;
    (void)user;
    if (n < 4) return 0;

    index = a[0]; dx = a[1]; dy = a[2]; scale = a[3];
    if (index < 0 || index >= IMAGE_COUNT) return 0;
    if (scale < 1) scale = 1;
    if (scale > 8) scale = 8;
    im = &g_images[index];
    base = vram_back_buffer;

    /* visible range: window and clip rectangle, whichever is tighter */
    x_lo = g_ox; if (clip_x > x_lo) x_lo = clip_x;
    y_lo = g_oy; if (clip_y > y_lo) y_lo = clip_y;
    x_hi = g_ox + g_ow; if (clip_x2 < x_hi) x_hi = clip_x2;
    y_hi = g_oy + g_oh; if (clip_y2 < y_hi) y_hi = clip_y2;

    for (sy = 0; sy < (int)im->height; sy++) {
        const unsigned char *srow = im->pixels + sy * im->width;
        for (ky = 0; ky < scale; ky++) {
            unsigned int *row;
            py = g_oy + dy + sy * scale + ky;
            if (py < y_lo || py >= y_hi) continue;
            row = (unsigned int *)(base + py * g_pitch);
            for (sx = 0; sx < (int)im->width; sx++) {
                unsigned int color = im->palette[srow[sx]] | 0xFF000000u;
                int xs = g_ox + dx + sx * scale;
                for (kx = 0; kx < scale; kx++) {
                    px = xs + kx;
                    if (px < x_lo || px >= x_hi) continue;
                    row[px] = color;
                }
            }
        }
    }
    return 0;
}

static int hf_width(int *a, int n, void *user)  { (void)a; (void)n; (void)user; return g_ow; }
static int hf_height(int *a, int n, void *user) { (void)a; (void)n; (void)user; return g_oh; }

/* PC speaker. This blocks for the duration of the note, exactly as the Java
   side does: there is no scheduler to play it in the background. */
/*
 * An effect. With a Sound Blaster present these become sampled clips, which do
 * not block; without one they fall back to the speaker, which does. The guest
 * asks for a pitch either way and never learns which happened.
 */
static int hf_beep(int *a, int n, void *user)
{
    int hz;
    (void)user;
    if (n < 2) return 0;
    hz = a[0];

    if (!g_audio_ready) {
        g_audio_ready = 1;
        sb16_init();
    }

    if (sb16_present()) {
        int clip = SND_STEP;
        if (hz < 200) clip = SND_BUMP;
        else if (hz < 700) clip = SND_PUSH;
        if (sb16_effect(clip)) return 0;
        return 0;
    }

    sys_beep(hz);
    sys_sleep(a[1]);
    sys_beep(0);
    g_sounding = 0;
    g_note_at = sys_get_ticks() + MUSIC_GAP;   /* let the effect breathe */
    return 0;
}

static int hf_key(int *a, int n, void *user)
{
    int k;
    (void)a; (void)n; (void)user;
    if (g_key_head == g_key_tail) return 0;
    k = g_keys[g_key_head];
    g_key_head = (g_key_head + 1) % KEY_SLOTS;
    return k;
}

static const wasm_host_entry g_hosts[] = {
    { "env", "set_color", hf_set_color },
    { "env", "fill_rect", hf_fill_rect },
    { "env", "draw_int",  hf_draw_int  },
    { "env", "draw_image", hf_draw_image },
    { "env", "width",     hf_width     },
    { "env", "height",    hf_height    },
    { "env", "key",       hf_key       },
    { "env", "beep",      hf_beep      },
    { "env", "music",     hf_music     }
};

/*
 * Called from the desktop's idle loop. `enabled` is false when the guest's
 * window is closed or minimised, which is how the music stops without the
 * guest having to know anything about windows.
 */
void wasm_music_tick(int enabled)
{
    const short *hz;
    const short *ms;
    int len, now;

    if (!enabled || !g_sound_on || g_track == 0) {
        music_silence();
        return;
    }

    if (g_track == 1) {
        hz = fanfare_hz; ms = fanfare_ms; len = 6;
    } else {
        hz = theme_hz;   ms = theme_ms;   len = 11;
    }

    now = sys_get_ticks();
    if (now - g_note_at < 0) g_note_at = now;      /* clock wrapped */
    if (now < g_note_at) return;

    if (g_resting) {
        music_silence();
        g_note_at = now + MUSIC_GAP;
        g_resting = 0;
        g_note++;
        if (g_note >= len) {
            g_note = 0;
            if (g_track == 1) {                    /* the fanfare plays once */
                g_track = 0;
                music_silence();
            }
        }
        return;
    }

    sys_beep(hz[g_note]);
    g_sounding = 1;
    g_note_at = now + ms[g_note] - MUSIC_GAP;
    g_resting = 1;
}

/* The desktop owns the sound setting, so it is pushed into the guest. */
void wasm_set_sound(int on)
{
    g_sound_on = on ? 1 : 0;
    if (!g_sound_on) music_silence();
    int args[1];
    if (!g_loaded || g_failed) return;
    args[0] = on;
    wasm_call(&g_mod, "set_sound", args, 1, 0);
}

/* Called from the desktop's key handler; codes are the guest's own small set. */
void wasm_push_key(int code)
{
    int next = (g_key_tail + 1) % KEY_SLOTS;
    if (code <= 0 || next == g_key_head) return;   /* full: drop rather than block */
    g_keys[g_key_tail] = code;
    g_key_tail = next;
}

/*
 * Runs the guest for one frame inside the given window rectangle.
 * Returns 1 on success, or the negated wasm_err so the desktop can say what
 * went wrong instead of silently drawing nothing.
 */
int wasm_guest_frame(int x, int y, int w, int h)
{
    wasm_err e;

    g_ox = x; g_oy = y; g_ow = w; g_oh = h;
    if (w <= 0 || h <= 0) return 1;
    if (g_failed) return g_failed;

    if (!g_loaded) {
        e = wasm_load(&g_mod, wasm_guest_module, (unsigned)sizeof(wasm_guest_module),
                      g_mem, (unsigned)sizeof(g_mem),
                      g_hosts, (unsigned)(sizeof(g_hosts) / sizeof(g_hosts[0])), 0);
        if (e != WASM_OK) { g_failed = -(int)e; return g_failed; }
        g_loaded = 1;
    }

    e = wasm_call(&g_mod, "frame", 0, 0, 0);
    return (e == WASM_OK) ? 1 : -(int)e;
}
