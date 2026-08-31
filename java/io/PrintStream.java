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

package java.io;

import kernel.Native;

public class PrintStream {
    public void print(String s) {
        if (s == null) s = "null";
        Native.sys(Native.SYS_SERIAL_PUTS, 0, 0, s, 0); 
    }
    
    public void println(String s) {
        print(s);
        Native.sys(Native.SYS_SERIAL_PUTC, '\r', 0, 0, 0);
        Native.sys(Native.SYS_SERIAL_PUTC, '\n', 0, 0, 0);
    }
    
    public void print(int value) {
        if (value == 0) {
            Native.sys(Native.SYS_SERIAL_PUTC, '0', 0, 0, 0);
            return;
        }
        int temp = value;
        int len = 0;
        boolean isNeg = false;
        if (temp < 0) { isNeg = true; temp = -temp; value = temp; len++; }
        int t2 = temp;
        while (t2 > 0) { len++; t2 /= 10; }
        
        int currX = len;
        byte[] chars = new byte[len];
        temp = value;
        while (temp > 0) {
            chars[--currX] = (byte)('0' + (temp % 10));
            temp /= 10;
        }
        if (isNeg) chars[0] = '-';
        
        for(int i = 0; i < len; i++) {
            Native.sys(Native.SYS_SERIAL_PUTC, chars[i], 0, 0, 0);
        }
    }
    
    public void println(int i) {
        print(i);
        Native.sys(Native.SYS_SERIAL_PUTC, '\r', 0, 0, 0);
        Native.sys(Native.SYS_SERIAL_PUTC, '\n', 0, 0, 0);
    }
}
