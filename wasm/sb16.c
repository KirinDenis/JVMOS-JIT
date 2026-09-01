/*
 * Sound Blaster 16 output, and the system's sound set.
 *
 * Clips are not stored anywhere. They are synthesised into the DMA buffer at
 * boot from a sine table and a linear envelope, which costs a few hundred
 * bytes of code instead of tens of kilobytes of samples, and lets the sounds
 * be tuned by changing numbers rather than by re-recording anything.
 *
 * Voices are *mixed* rather than appended, so a clip can hold several notes
 * sounding at once. That is the one thing the PC speaker cannot do at all: it
 * has a single square wave generator, so its "chords" could only ever be
 * arpeggios. The boot sound is a real chord because of this.
 *
 * The buffer sits at a fixed address rather than in BSS because the 8237 DMA
 * controller addresses memory as a 16-bit offset inside a 64KB page: it must
 * be below 16MB and must not straddle a page boundary. 0x00F00000 is page
 * aligned, below the limit, and clear of both heaps; 64KB fills that page
 * exactly, which is the largest a single transfer can be.
 */

extern int sys_inb(int port);
extern void sys_outb(int port, int value);
extern int sys_get_ticks(void);
extern void sys_beep(int hz);
extern void sys_sleep(int ms);

#define SB_BASE     0x220
#define SB_RESET    (SB_BASE + 0x6)
#define SB_READ     (SB_BASE + 0xA)
#define SB_WRITE    (SB_BASE + 0xC)   /* bit 7 set means the DSP is busy */
#define SB_RSTATUS  (SB_BASE + 0xE)   /* bit 7 set means data is available */

#define SB_RATE     22050
#define DMA_BUFFER  0x00F00000u
#define DMA_BYTES   (64 * 1024)

/* sin(2*pi*i/32) scaled to +-127 */
static const signed char SINE[32] = {
       0,   25,   49,   71,   90,  106,  117,  125,
     127,  125,  117,  106,   90,   71,   49,   25,
       0,  -25,  -49,  -71,  -90, -106, -117, -125,
    -127, -125, -117, -106,  -90,  -71,  -49,  -25
};

/* Clip ids, shared with host.c and kernel/Boot.java through SYS_SND_PLAY. */
#define SND_BOOT   0
#define SND_CLICK  1
#define SND_DENY   2
#define SND_STEP   3
#define SND_PUSH   4
#define SND_BUMP   5
#define SND_WIN    6
#define SND_COUNT  7

/* Pitch used when falling back to the speaker, and how long to hold it. */
static const short FALLBACK_HZ[SND_COUNT] = { 880, 1800, 200, 1500, 520, 150, 988 };
static const short FALLBACK_MS[SND_COUNT] = {  90,   12,  60,    9,  34,  55,  120 };

static int g_present;
static int g_probed;
static int g_offset[SND_COUNT];
static int g_length[SND_COUNT];
static int g_busy_until;

static void io_delay(void)
{
    int i;
    for (i = 0; i < 8; i++) sys_inb(0x80);
}

static void dsp_write(int value)
{
    int guard = 0;
    /* sys_inb only sets AL, so mask before testing the busy bit */
    while ((sys_inb(SB_WRITE) & 0x80) != 0 && guard < 20000) guard++;
    sys_outb(SB_WRITE, value);
}

static int dsp_read(void)
{
    int guard = 0;
    while ((sys_inb(SB_RSTATUS) & 0x80) == 0 && guard < 20000) guard++;
    if (guard >= 20000) return -1;
    return sys_inb(SB_READ) & 0xFF;
}

static void fill_silence(unsigned char *buf, int at, int samples)
{
    int i;
    for (i = 0; i < samples && at + i < DMA_BYTES; i++) buf[at + i] = 128;
}

/*
 * Adds one enveloped sine into the buffer, on top of whatever is already
 * there, clamping the sum. The phase accumulator carries 12 fractional bits,
 * which keeps the step inside 32 bits even for the highest note used here.
 */
static void mix_tone(unsigned char *buf, int at, int delay_ms, int hz, int ms,
                     int vol_from, int vol_to)
{
    int start = at + SB_RATE * delay_ms / 1000;
    int samples = SB_RATE * ms / 1000;
    unsigned phase = 0;
    unsigned step = ((unsigned)hz * 32u * 4096u) / SB_RATE;
    int i;

    if (samples <= 0) return;
    for (i = 0; i < samples; i++) {
        int at_i = start + i;
        int current, volume, sample, mixed;
        if (at_i >= DMA_BYTES) break;
        current = (int)buf[at_i] - 128;
        volume = vol_from + (vol_to - vol_from) * i / samples;
        sample = SINE[(phase >> 12) & 31] * volume / 255;
        mixed = current + sample;
        if (mixed > 127) mixed = 127;
        if (mixed < -128) mixed = -128;
        buf[at_i] = (unsigned char)(128 + mixed);
        phase += step;
    }
}

static int reserve(int *at, int id, int ms)
{
    unsigned char *buf = (unsigned char *)DMA_BUFFER;
    int samples = SB_RATE * ms / 1000;
    if (*at + samples > DMA_BYTES) samples = DMA_BYTES - *at;
    fill_silence(buf, *at, samples);
    g_offset[id] = *at;
    g_length[id] = samples;
    *at += samples;
    return g_offset[id];
}

static void build_clips(void)
{
    unsigned char *buf = (unsigned char *)DMA_BUFFER;
    int at = 0, base;

    /* Boot: a C major chord that builds up, four voices overlapping. */
    base = reserve(&at, SND_BOOT, 1000);
    mix_tone(buf, base,   0, 262, 950, 90, 0);    /* C4 */
    mix_tone(buf, base, 140, 330, 810, 80, 0);    /* E4 */
    mix_tone(buf, base, 280, 392, 670, 75, 0);    /* G4 */
    mix_tone(buf, base, 420, 523, 530, 70, 0);    /* C5 */

    base = reserve(&at, SND_CLICK, 35);
    mix_tone(buf, base, 0, 1900, 30, 120, 0);
    mix_tone(buf, base, 0, 2850, 22, 60, 0);      /* a fifth above, for body */

    base = reserve(&at, SND_DENY, 150);
    mix_tone(buf, base, 0, 200, 140, 200, 0);
    mix_tone(buf, base, 0, 208, 140, 140, 0);     /* detuned, so it beats */

    base = reserve(&at, SND_STEP, 45);
    mix_tone(buf, base, 0, 1200, 40, 190, 0);

    base = reserve(&at, SND_PUSH, 95);
    mix_tone(buf, base, 0, 320, 90, 230, 0);
    mix_tone(buf, base, 0, 161, 90, 120, 0);      /* an octave down, for weight */

    base = reserve(&at, SND_BUMP, 125);
    mix_tone(buf, base, 0, 110, 120, 240, 0);
    mix_tone(buf, base, 0, 165, 60, 90, 0);

    /* The level fanfare from the original game's sound.rs, with the notes
       allowed to ring into each other instead of being played one at a time. */
    base = reserve(&at, SND_WIN, 520);
    mix_tone(buf, base,   0, 392, 300, 110, 0);   /* G4 */
    mix_tone(buf, base, 110, 494, 300, 110, 0);   /* B4 */
    mix_tone(buf, base, 220, 587, 300, 110, 0);   /* D5 */
    mix_tone(buf, base, 330, 784, 190, 130, 0);   /* G5 */
}

static int sb16_probe(void)
{
    sys_outb(SB_RESET, 1);
    io_delay();
    sys_outb(SB_RESET, 0);

    if (dsp_read() != 0xAA) return 0;

    dsp_write(0xD1);                 /* speaker on */
    build_clips();
    return 1;
}

static void audio_init(void)
{
    if (g_probed) return;
    g_probed = 1;
    g_present = sb16_probe();
}

int sb16_present(void)
{
    audio_init();
    return g_present;
}

static void sb16_transfer(int offset, int length)
{
    unsigned addr = DMA_BUFFER + (unsigned)offset;
    int last = length - 1;

    /* 8237 channel 1: mask, program, unmask */
    sys_outb(0x0A, 0x05);                          /* mask channel 1 */
    sys_outb(0x0C, 0x00);                          /* clear the flip-flop */
    sys_outb(0x0B, 0x49);                          /* single, read memory, ch 1 */
    sys_outb(0x83, (int)((addr >> 16) & 0xFF));    /* page for channel 1 */
    sys_outb(0x02, (int)(addr & 0xFF));
    sys_outb(0x02, (int)((addr >> 8) & 0xFF));
    sys_outb(0x0C, 0x00);
    sys_outb(0x03, last & 0xFF);
    sys_outb(0x03, (last >> 8) & 0xFF);
    sys_outb(0x0A, 0x01);                          /* unmask channel 1 */

    dsp_write(0x41);                               /* output sampling rate */
    dsp_write((SB_RATE >> 8) & 0xFF);
    dsp_write(SB_RATE & 0xFF);
    dsp_write(0x14);                               /* single cycle 8-bit output */
    dsp_write(last & 0xFF);
    dsp_write((last >> 8) & 0xFF);
}

/*
 * Plays one of the system sounds. On a Sound Blaster this is a mixed PCM clip
 * and returns immediately; without one it falls back to a single speaker tone,
 * which does block for the length of the note.
 *
 * Nothing waits for the card: it raises IRQ5, which the PIC masks, so the end
 * of a clip is tracked against the system clock instead.
 */
void audio_play(int id)
{
    int now;

    if (id < 0 || id >= SND_COUNT) return;
    audio_init();

    if (g_present) {
        now = sys_get_ticks();
        if (now < g_busy_until) return;            /* still sounding */
        sys_inb(SB_RSTATUS);                       /* acknowledge a pending IRQ */
        sb16_transfer(g_offset[id], g_length[id]);
        g_busy_until = now + (g_length[id] * 1000 / SB_RATE) + 10;
        return;
    }

    sys_beep(FALLBACK_HZ[id]);
    sys_sleep(FALLBACK_MS[id]);
    sys_beep(0);
}
