package analysis;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Streaming parser for the waveform BIN container. */
public final class BinFileParser {
    private static final int FIXED_HEADER_AFTER_LENGTH = 4 + 8 + 1 + 15;
    private static final int COLUMN_DESCRIPTOR_SIZE = 32;
    private static final int FIELD_NAME_SIZE = 30;

    private final ByteOrder byteOrder;

    public BinFileParser() {
        this(ByteOrder.LITTLE_ENDIAN);
    }

    public BinFileParser(ByteOrder byteOrder) {
        this.byteOrder = byteOrder;
    }

    public ParseResult parse(Path file, int batchSize, BatchConsumer consumer) throws IOException {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        if (consumer == null) throw new IllegalArgumentException("consumer must not be null");

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            long headerRangeLength = Integer.toUnsignedLong(readInt(channel));
            long dataStart = 4L + headerRangeLength;
            if (dataStart > fileSize) {
                throw new BinFormatException("Header range exceeds file size: " + headerRangeLength);
            }

            long columnCountValue = Integer.toUnsignedLong(readInt(channel));
            if (columnCountValue == 0 || columnCountValue > Integer.MAX_VALUE) {
                throw new BinFormatException("Invalid column count: " + columnCountValue);
            }
            int columnCount = (int) columnCountValue;
            long version = readLong(channel);
            boolean compressed = Byte.toUnsignedInt(readByte(channel)) == 1;
            skipFully(channel, 15);

            long requiredHeaderBytes = 4L + FIXED_HEADER_AFTER_LENGTH
                    + (long) columnCount * COLUMN_DESCRIPTOR_SIZE;
            if (dataStart < requiredHeaderBytes) {
                throw new BinFormatException("Header range ends before all column descriptors");
            }

            List<Column> columns = new ArrayList<>(columnCount);
            int rowLength = 0;
            for (int index = 0; index < columnCount; index++) {
                ByteBuffer descriptor = readBuffer(channel, COLUMN_DESCRIPTOR_SIZE);
                int typeId = Byte.toUnsignedInt(descriptor.get());
                int dataLength = Byte.toUnsignedInt(descriptor.get());
                BinDataType type;
                try {
                    type = BinDataType.fromId(typeId);
                } catch (IllegalArgumentException ex) {
                    throw new BinFormatException("Column " + index + " has unknown type " + typeId, ex);
                }
                validateDataLength(index, type, dataLength);
                byte[] nameBytes = new byte[FIELD_NAME_SIZE];
                descriptor.get(nameBytes);
                String name = decodeName(nameBytes);
                if (name.isEmpty()) name = "column_" + index;
                columns.add(new Column(index, type, dataLength, name));
                rowLength = Math.addExact(rowLength, dataLength);
            }

            // The first four bytes describe the complete header range. Any extension bytes that are
            // unknown to this parser are skipped before bulk row parsing begins.
            channel.position(dataStart);
            Header header = new Header(headerRangeLength, columnCount, version, compressed,
                    Collections.unmodifiableList(columns), dataStart, rowLength);

            if (compressed) {
                parseCompressedPayload(channel, header, batchSize, consumer);
                return new ParseResult(header, 0);
            }

            long rowCount = parseUncompressedRows(channel, header, batchSize, consumer);
            return new ParseResult(header, rowCount);
        } catch (ArithmeticException ex) {
            throw new BinFormatException("Total row length is too large", ex);
        }
    }

    private long parseUncompressedRows(FileChannel channel, Header header, int batchSize,
                                       BatchConsumer consumer) throws IOException {
        long remaining = channel.size() - channel.position();
        if (remaining % header.rowLength != 0) {
            throw new BinFormatException("Payload length " + remaining
                    + " is not divisible by row length " + header.rowLength);
        }
        long totalRows = remaining / header.rowLength;
        long parsedRows = 0;
        while (parsedRows < totalRows) {
            int rowsInBatch = (int) Math.min(batchSize, totalRows - parsedRows);
            Object[][] values = new Object[header.columnCount][rowsInBatch];
            ByteBuffer rows = readBuffer(channel, Math.multiplyExact(rowsInBatch, header.rowLength));
            for (int row = 0; row < rowsInBatch; row++) {
                for (Column column : header.columns) {
                    values[column.index][row] = readValue(rows, column);
                }
            }
            consumer.accept(new DataBatch(parsedRows, rowsInBatch, header.columns, values));
            parsedRows += rowsInBatch;
        }
        return totalRows;
    }

    /** Reserved compression hook. Implement decompression here when the compressed format is known. */
    private void parseCompressedPayload(FileChannel channel, Header header, int batchSize,
                                        BatchConsumer consumer) throws IOException {
        throw new UnsupportedOperationException("Compressed BIN payload parsing is not implemented");
    }

    private Object readValue(ByteBuffer source, Column column) {
        return switch (column.type) {
            case INT8 -> source.get();
            case INT16 -> source.getShort();
            case INT32 -> source.getInt();
            case INT64 -> source.getLong();
            case FLOAT32 -> source.getFloat();
            case FLOAT64 -> source.getDouble();
            case UTF8 -> decodeText(readBytes(source, column.dataLength));
            case RAW -> readBytes(source, column.dataLength);
        };
    }

    private static void validateDataLength(int index, BinDataType type, int length) throws BinFormatException {
        if (length == 0) throw new BinFormatException("Column " + index + " has zero data length");
        if (type.fixedLength() > 0 && type.fixedLength() != length) {
            throw new BinFormatException("Column " + index + " type " + type
                    + " requires " + type.fixedLength() + " bytes, got " + length);
        }
    }

    private static byte[] readBytes(ByteBuffer source, int length) {
        byte[] result = new byte[length];
        source.get(result);
        return result;
    }

    private static String decodeName(byte[] bytes) {
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) end++;
        return new String(bytes, 0, end, StandardCharsets.UTF_8).trim();
    }

    private static String decodeText(byte[] bytes) {
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private byte readByte(FileChannel channel) throws IOException {
        return readBuffer(channel, 1).get();
    }

    private int readInt(FileChannel channel) throws IOException {
        return readBuffer(channel, Integer.BYTES).getInt();
    }

    private long readLong(FileChannel channel) throws IOException {
        return readBuffer(channel, Long.BYTES).getLong();
    }

    private ByteBuffer readBuffer(FileChannel channel, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(byteOrder);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw new EOFException("Unexpected end of BIN file");
        }
        return buffer.flip().order(byteOrder);
    }

    private static void skipFully(FileChannel channel, long bytes) throws IOException {
        long target = channel.position() + bytes;
        if (target > channel.size()) throw new EOFException("Unexpected end of BIN file while skipping bytes");
        channel.position(target);
    }

    @FunctionalInterface
    public interface BatchConsumer {
        void accept(DataBatch batch) throws IOException;
    }

    public record Column(int index, BinDataType type, int dataLength, String name) { }

    public record Header(long headerRangeLength, int columnCount, long version, boolean compressed,
                         List<Column> columns, long dataStart, int rowLength) { }

    public record DataBatch(long firstRow, int rowCount, List<Column> columns, Object[][] values) {
        public Object value(int column, int row) { return values[column][row]; }
    }

    public record ParseResult(Header header, long rowCount) { }

    public static final class BinFormatException extends IOException {
        private static final long serialVersionUID = 1L;
        public BinFormatException(String message) { super(message); }
        public BinFormatException(String message, Throwable cause) { super(message, cause); }
    }
}
