package com.mtm.worldgen.webmap;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the NBT of saved chunks into plain Java values, without Minecraft: compounds become maps, lists become lists,
 * long arrays stay long arrays. The map reads region files on its own threads, apart from the game, and works the
 * same in every Minecraft version.
 */
final class Nbt {
    private static final int END = 0;
    private static final int BYTE = 1;
    private static final int SHORT = 2;
    private static final int INT = 3;
    private static final int LONG = 4;
    private static final int FLOAT = 5;
    private static final int DOUBLE = 6;
    private static final int BYTE_ARRAY = 7;
    private static final int STRING = 8;
    private static final int LIST = 9;
    private static final int COMPOUND = 10;
    private static final int INT_ARRAY = 11;
    private static final int LONG_ARRAY = 12;
    /** Deeper nesting than any chunk has means a damaged file. */
    private static final int MAX_DEPTH = 64;

    private Nbt() {
    }

    /** Reads an uncompressed named root tag, which must be a compound. */
    static Map<String, Object> read(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(input);
        int type = data.readUnsignedByte();
        if (type != COMPOUND) {
            throw new IOException("Root tag is not a compound: " + type);
        }
        data.readUTF();
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) value(data, COMPOUND, 0);
        return root;
    }

    private static Object value(DataInput data, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nested too deep");
        }
        switch (type) {
            case BYTE:
                return data.readByte();
            case SHORT:
                return data.readShort();
            case INT:
                return data.readInt();
            case LONG:
                return data.readLong();
            case FLOAT:
                return data.readFloat();
            case DOUBLE:
                return data.readDouble();
            case BYTE_ARRAY: {
                byte[] bytes = new byte[length(data)];
                data.readFully(bytes);
                return bytes;
            }
            case STRING:
                return data.readUTF();
            case LIST: {
                int elementType = data.readUnsignedByte();
                int size = length(data);
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(value(data, elementType, depth + 1));
                }
                return list;
            }
            case COMPOUND: {
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    int elementType = data.readUnsignedByte();
                    if (elementType == END) {
                        return map;
                    }
                    String name = data.readUTF();
                    map.put(name, value(data, elementType, depth + 1));
                }
            }
            case INT_ARRAY: {
                int[] ints = new int[length(data)];
                for (int i = 0; i < ints.length; i++) {
                    ints[i] = data.readInt();
                }
                return ints;
            }
            case LONG_ARRAY: {
                long[] longs = new long[length(data)];
                for (int i = 0; i < longs.length; i++) {
                    longs[i] = data.readLong();
                }
                return longs;
            }
            default:
                throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static int length(DataInput data) throws IOException {
        int length = data.readInt();
        if (length < 0 || length > 64 * 1024 * 1024) {
            throw new IOException("Bad NBT length " + length);
        }
        return length;
    }
}
