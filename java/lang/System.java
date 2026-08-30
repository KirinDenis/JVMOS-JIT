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

package java.lang;

import java.io.PrintStream;

public final class System {
    
    public static final PrintStream out = new PrintStream();

    public static long currentTimeMillis() {
        // Syscall 18 = SYS_GET_TICKS
        return kernel.Native.sys(18, 0, 0, 0, 0); 
    }

    public static void exit(int status) {
        // Syscall 17 = SYS_EXIT
        kernel.Native.sys(17, status, 0, 0, 0);
    }

    // Copia manual para eludir punteros inseguros de C
    public static void arraycopy(Object src, int srcPos, Object dest, int destPos, int length) {
        if (src instanceof byte[] && dest instanceof byte[]) {
            byte[] s = (byte[]) src;
            byte[] d = (byte[]) dest;
            for (int i = 0; i < length; i++) {
                d[destPos + i] = s[srcPos + i];
            }
        } else if (src instanceof int[] && dest instanceof int[]) {
            int[] s = (int[]) src;
            int[] d = (int[]) dest;
            for (int i = 0; i < length; i++) {
                d[destPos + i] = s[srcPos + i];
            }
        } else if (src instanceof Object[] && dest instanceof Object[]) {
            Object[] s = (Object[]) src;
            Object[] d = (Object[]) dest;
            for (int i = 0; i < length; i++) {
                d[destPos + i] = s[srcPos + i];
            }
        }
    }
}
