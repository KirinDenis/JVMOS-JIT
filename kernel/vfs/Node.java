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

package kernel.vfs;

public class Node {
    public String name;
    public boolean isDir;
    public Node[] children;
    public int childCount;
    public Node parent;

    // Constructor
    public Node(String name, boolean isDir, Node parent) {
        this.name = name;
        this.isDir = isDir;
        this.parent = parent;
        this.childCount = 0;
        
        if (isDir) {
            this.children = new Node[8]; // Límite actual de tu VFS Baremetal
        } else {
            this.children = null;
        }
    }

    // Añadir hijos de forma orientada a objetos
    public boolean addChild(Node child) {
        if (!this.isDir || this.childCount >= 8 || child == null) {
            return false; 
        }
        
        child.parent = this;
        this.children[this.childCount] = child;
        this.childCount++;
        return true;
    }

    // Eliminar hijos compactando el arreglo
    public void removeChild(Node target) {
        if (!this.isDir || this.childCount == 0 || target == null) {
            return;
        }
        
        int idx = -1;
        for (int i = 0; i < this.childCount; i++) {
            if (this.children[i] == target) {
                idx = i;
                break;
            }
        }
        
        if (idx != -1) {
            // Desplazar los elementos hacia la izquierda para tapar el hueco
            for (int i = idx; i < this.childCount - 1; i++) {
                this.children[i] = this.children[i + 1];
            }
            this.childCount--;
            this.children[this.childCount] = null; // Limpiar la referencia final
        }
    }
}
