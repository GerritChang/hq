package analysis;

/** Type identifiers stored in the first byte of each 32-byte column descriptor. */
public enum BinDataType {
    INT8(1, 1),
    INT16(2, 2),
    INT32(3, 4),
    INT64(4, 8),
    FLOAT32(5, 4),
    FLOAT64(6, 8),
    UTF8(7, -1),
    RAW(8, -1);

    private final int id;
    private final int fixedLength;

    BinDataType(int id, int fixedLength) {
        this.id = id;
        this.fixedLength = fixedLength;
    }

    public int id() { return id; }

    public int fixedLength() { return fixedLength; }

    public static BinDataType fromId(int id) {
        for (BinDataType value : values()) {
            if (value.id == id) return value;
        }
        throw new IllegalArgumentException("Unknown BIN data type id: " + id);
    }
}
