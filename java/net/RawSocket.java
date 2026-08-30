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

package java.net;

import kernel.Native;

public class RawSocket {
    private boolean initialized = false;

    // Inicializa la RTL8139 pasando su puerto I/O base de PCI
    public RawSocket(int ioPortBase) {
		// Syscall 23: Inicializar Tarjeta de Red
        int status = Native.sys(Native.SYS_RTL8139_INIT, ioPortBase, 0, 0, 0);
        if (status == 0) { 
            initialized = true;
        }
    }

    // Enviar una trama Ethernet cruda
    public void send(DatagramPacket packet) {
        if (!initialized || packet == null) return;
        
        // Pasa la longitud en 'b' y el byte[] en 'c' (Object)
		// Syscall 24: Enviar paquete de Red
        Native.sys(Native.SYS_RTL8139_SEND, 0, packet.getLength(), packet.getData(), 0);
    }

    // Recibir una trama Ethernet cruda en el buffer del paquete
    public int receive(DatagramPacket packet) {
        if (!initialized || packet == null) return -1;
        
        // El HAL llena el byte[] y devuelve los bytes leídos
		// Syscall 25: Recibir paquete de Red
        int bytesRead = Native.sys(Native.SYS_NET_RECEIVE, 0, packet.getLength(), packet.getData(), 0);
        if (bytesRead > 0) {
            packet.setLength(bytesRead);
        }
        return bytesRead;
    }
}
