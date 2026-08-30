package java.lang;

public final class String {
    private final byte[] value;
	
    public String(byte[] bytes) {
        this.value = bytes;
    }
}