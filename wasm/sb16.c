/*
 * Sound Blaster 16 output.
 *
 * Effects are short PCM clips played through the card; music stays on the PC
 * speaker, because looping music would need auto-initialised DMA and a mixer,
 * and one blocking transfer per effect is all this needs.
 *
 * The clips are not stored anywhere. They are synthesised into the DMA buffer
 * at boot from a sine table and a linear envelope, which costs a few hundred
 * bytes of code instead of a few tens of kilobytes of samples, and lets the
 * sounds be tuned by changing numbers rather than by re-recording anything.
 *
 * The buffer sits at a fixed address rather than in BSS because the 8237 DMA
 * controller addresses memory as a 16-bit offset inside a 64KB page: the
 * buffer must be below 16MB and must not straddle a page boundary. 0x00F00000
 * is page aligned, below the limit, and clear of both heaps.
 */

extern int sys_inb(int port);
extern void sys_outb(int port, int value);
extern int sys_get_ticks(void);

#define SB_BASE     0x220
#define SB_RESET    (SB_BASE + 0x6)
#define SB_READ     (SB_BASE + 0xA)
#define SB_WRITE    (SB_BASE + 0xC)   /* bit 7 set means the DSP is busy */
#define SB_RSTATUS  (SB_BASE + 0xE)   /* bit 7 set means data is available */

#define SB_RATE     22050
#define DMA_BUFFER  0x00F00000u
#define DMA_BYTES   (32 * 1024)

/* sin(2*pi*i/32) scaled to +-127 */
static const signed char SINE[32] = {
       0,   25,   49,   71,   90,  106,  117,  125,
     127,  125,  117,  106,   90,   71,   49,   25,
       0,  -25,  -49,  -71,  -90, -106, -117, -125,
    -127, -125, -117, -106,  -90,  -71,  -49,  -25
};

enum { SND_STEP = 0, SND_PUSH, SND_BUMP, SND_WIN, SND_COUNT };

static int g_present;
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

/*
 * Writes one enveloped tone into the buffer and returns the next free offset.
 * The phase accumulator carries 12 fractional bits, which keeps the step well
 * inside 32 bits even for the highest note used here.
 */
static int gen_tone(unsigned char *buf, int at, int hz, int ms, int vol_from, int vol_to)
{
    int samples = SB_RATE * ms / 1000;
    unsigned phase = 0;
    unsigned step = ((unsigned)hz * 32u * 4096u) / SB_RATE;
    int i;

    if (at + samples > DMA_BYTES) samples = DMA_BYTES - at;
    for (i = 0; i < samples; i++) {
        int volume = vol_from + (vol_to - vol_from) * i / (samples ? samples : 1);
        int sample = SINE[(phase >> 12) & 31] * volume / 255;
        buf[at + i] = (unsigned char)(128 + sample);
        phase += step;
    }
    return at + samples;
}

static void build_clips(void)
{
    unsigned char *buf = (unsigned char *)DMA_BUFFER;
    int at = 0, start;

    start = at;
    at = gen_tone(buf, at, 1200, 40, 190, 0);
    g_offset[SND_STEP] = start;
    g_length[SND_STEP] = at - start;

    start = at;
    at = gen_tone(buf, at, 320, 90, 255, 0);
    g_offset[SND_PUSH] = start;
    g_length[SND_PUSH] = at - start;

    start = at;
    at = gen_tone(buf, at, 110, 120, 255, 0);
    g_offset[SND_BUMP] = start;
    g_length[SND_BUMP] = at - start;

    /* the level fanfare from the original game's sound.rs */
    start = at;
    at = gen_tone(buf, at, 784, 130, 220, 200);
    at = gen_tone(buf, at, 988, 130, 220, 200);
    at = gen_tone(buf, at, 1319, 220, 230, 0);
    g_offset[SND_WIN] = start;
    g_length[SND_WIN] = at - start;
}

int sb16_init(void)
{
    sys_outb(SB_RESET, 1);
    io_delay();
    sys_outb(SB_RESET, 0);

    if (dsp_read() != 0xAA) {
        g_present = 0;
        return 0;
    }

    dsp_write(0xD1);                 /* speaker on */
    build_clips();
    g_present = 1;
    return 1;
}

int sb16_present(void)
{
    return g_present;
}

static void sb16_play(int offset, int length)
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
 * Starts one of the built-in clips. Returns 0 when the card is absent or a
 * clip is still playing, so the caller can fall back to the speaker.
 *
 * Nothing waits for the transfer to finish: the card raises IRQ5, which is
 * masked in the PIC, so completion is tracked against the system clock
 * instead. That keeps the machine responsive while a sound plays.
 */
int sb16_effect(int id)
{
    int now;
    if (!g_present || id < 0 || id >= SND_COUNT) return 0;

    now = sys_get_ticks();
    if (now < g_busy_until) return 0;

    sys_inb(SB_RSTATUS);             /* acknowledge any pending 8-bit IRQ */
    sb16_play(g_offset[id], g_length[id]);
    g_busy_until = now + (g_length[id] * 1000 / SB_RATE) + 10;
    return 1;
}
