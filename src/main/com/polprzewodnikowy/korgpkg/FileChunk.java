package com.polprzewodnikowy.korgpkg;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Created by korgeaux on 09.05.2016.
 */
public class FileChunk extends Chunk {

    public final static byte COMPRESSION_RAW = 0;
    public final static byte COMPRESSION_ZLIB = 1;

    public final static int ATTR_ARCHIVE = 0x1000;
    public final static int ATTR_READ_ONLY = 0x2000;
    public final static int ATTR_SYSTEM = 0x4000;
    public final static int ATTR_HIDDEN = 0x8000;

    short attributes;
    short order;
    byte compressionType;
    String name;
    String date;
    String time;
    byte[] data;

    public FileChunk() {
        id = FILE;
        attributes = ATTR_ARCHIVE | ATTR_READ_ONLY | ATTR_SYSTEM;
        order = -1;
        compressionType = COMPRESSION_RAW;
        name = "";
        date = "";
        time = "";
        data = new byte[0];
    }

    public int getAttributes() {
        return attributes;
    }

    public void setAttributes(int attributes) {
        this.attributes = (short) attributes;
    }

    public short getOrder() {
        return order;
    }

    public void setOrder(short order) {
        this.order = order;
    }

    public byte getCompressionType() {
        return compressionType;
    }

    public void setCompressionType(byte compressionType) {
        this.compressionType = compressionType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Date getDateTime() {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm");
        Date tmpDate = new Date();
        try {
            tmpDate = simpleDateFormat.parse(date + " " + time);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return tmpDate;
    }

    public void setDateTime(Date date) {
        this.date = String.format("%tm/%<td/%<ty", date);
        this.time = String.format("%tH:%<tM", date);
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public byte[] getData() {
        return data;
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append("[" + id + " FileChunk]: ");
        str.append(name + " | " + date + " " + time + " | [");
        if ((attributes & ATTR_ARCHIVE) == ATTR_ARCHIVE)
            str.append("A");
        if ((attributes & ATTR_READ_ONLY) == ATTR_READ_ONLY)
            str.append("R");
        if ((attributes & ATTR_SYSTEM) == ATTR_SYSTEM)
            str.append("S");
        if ((attributes & ATTR_HIDDEN) == ATTR_HIDDEN)
            str.append("H");
        str.append("] | ");
        if (compressionType == COMPRESSION_RAW)
            str.append("RAW");
        else if (compressionType == COMPRESSION_ZLIB)
            str.append("ZLIB");
        str.append(" | " + order);
        return str.toString();
    }

    @Override
    public void load(RandomAccessFile reader, int size) throws IOException {
        reader.skipBytes(16);
        reader.skipBytes(4);
        attributes = Short.reverseBytes(reader.readShort());
        order = Short.reverseBytes(reader.readShort());
        int dataSize = Integer.reverseBytes(reader.readInt());
        compressionType = reader.readByte();
        name = readString(reader);
        date = readString(reader);
        time = readString(reader);

        data = new byte[dataSize];

        if (compressionType == COMPRESSION_RAW) {
            reader.read(data, 0, dataSize);
        } else if (compressionType == COMPRESSION_ZLIB) {
            int index = 0;
            while (true) {
                int blockType = Integer.reverseBytes(reader.readInt());
                if (blockType != 0x00000100)
                    break;
                int compressedBlockSize = Integer.reverseBytes(reader.readInt()) - 4;
                int uncompressedBlockSize = reader.readInt();
                byte[] compressed = new byte[compressedBlockSize];
                reader.read(compressed, 0, compressedBlockSize);

                try {
                    Inflater inflater = new Inflater();
                    inflater.setInput(compressed);
                    byte[] uncompressed = new byte[uncompressedBlockSize];
                    inflater.inflate(uncompressed);
                    System.arraycopy(uncompressed, 0, data, index, uncompressedBlockSize);
                } catch (DataFormatException e) {
                    e.printStackTrace();
                }

                index += uncompressedBlockSize;
                int rem = compressedBlockSize % 4;
                if (rem != 0)
                    reader.skipBytes(4 - rem);
            }
        }
    }

    @Override
    public void save(RandomAccessFile writer) throws IOException {
        byte[] hash = new byte[16];
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(data);
            hash = md5.digest();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        writer.writeInt(Integer.reverseBytes(id));
        long offset = writer.getFilePointer();
        writer.write(new byte[4]);
        writer.write(hash);
        writer.write(new byte[4]);
        writer.writeShort(Short.reverseBytes(attributes));
        writer.writeShort(Short.reverseBytes(order));
        writer.writeInt(Integer.reverseBytes(data.length));
        writer.writeByte(compressionType);
        writeString(writer, name);
        writeString(writer, date);
        writeString(writer, time);

        if (compressionType == COMPRESSION_RAW) {
            writer.write(data);
        } else if (compressionType == COMPRESSION_ZLIB) {
            int index = 0;
            int remain = data.length;
            if (data.length > 0) {
                do {
                    int blockSize;
                    if (remain > 0x00100000) {
                        blockSize = 0x00100000;
                    } else {
                        blockSize = remain;
                    }
                    byte compressed[] = new byte[0x00100000];
                    Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, false);
                    deflater.setInput(data, index, blockSize);
                    deflater.finish();
                    int compressedBlockSize = deflater.deflate(compressed);
                    deflater.end();
                    writer.writeInt(Integer.reverseBytes(0x00000100));
                    writer.writeInt(Integer.reverseBytes(compressedBlockSize + 4));
                    writer.writeInt(blockSize);
                    writer.write(compressed, 0, compressedBlockSize);

                    int rem = compressedBlockSize % 4;
                    if (rem != 0)
                        writer.write(new byte[4 - rem]);

                    index += 0x00100000;
                    remain -= 0x00100000;
                } while (index < data.length);
            }
            writer.writeInt(Integer.reverseBytes(0x00000101));
            writer.writeInt(Integer.reverseBytes(0x00000000));
        }

        int size = (int) (writer.getFilePointer() - offset - 4);
        writer.seek(offset);
        writer.writeInt(Integer.reverseBytes(size));
    }

    @Override
    public void export(String path) throws IOException {
        String tmpName = name.charAt(0) == '/' ? name.substring(1) : name;
        Path tmpPath = Paths.get(path, tmpName);
        tmpPath.getParent().toFile().mkdirs();
        FileOutputStream fileOutputStream = new FileOutputStream(tmpPath.toFile());
        fileOutputStream.write(data);
        fileOutputStream.close();
    }

}
