/*MIT License

Copyright (c) 2026 Allan (Slam)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.*/

package java.awt;

public class Color {
    public static final Color BLACK       = new Color(0x000000);
    public static final Color WHITE       = new Color(0xFFFFFF);
    public static final Color RED         = new Color(0xFF0000);
    public static final Color GREEN       = new Color(0x008000);
    public static final Color BLUE        = new Color(0x0000FF);
    public static final Color YELLOW      = new Color(0xFFFF00);
    public static final Color GRAY        = new Color(0x808080);
    public static final Color LIGHT_GRAY  = new Color(0xC0C0C0);
    public static final Color DARK_GRAY   = new Color(0x404040);
    public static final Color TRANSPARENT = new Color(0x00000000); // Dependiendo de tu alfa

    private final int rgb;

    public Color(int rgb) {
        this.rgb = rgb;
    }

    public Color(int r, int g, int b) {
        this.rgb = (0xFF << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public int getRGB() {
        return rgb;
    }
}
