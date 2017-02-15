/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.common.utils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.io.FileNotFoundException;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Properties;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.common.KafkaException;

public class Utils {

    // This matches URIs of formats: host:port and protocol:\\host:port
    // IPv6 is supported with [ip] pattern
    private static final Pattern HOST_PORT_PATTERN = Pattern.compile(".*?\\[?([0-9a-zA-Z\\-%._:]*)\\]?:([0-9]+)");

    public static final String NL = System.getProperty("line.separator");

    private static final Logger log = LoggerFactory.getLogger(Utils.class);

    /**
     * Get a sorted list representation of a collection.
     * @param collection The collection to sort
     * @param <T> The class of objects in the collection
     * @return An unmodifiable sorted list with the contents of the collection
     */
    public static <T extends Comparable<? super T>> List<T> sorted(Collection<T> collection) {
        List<T> res = new ArrayList<>(collection);
        Collections.sort(res);
        return Collections.unmodifiableList(res);
    }

    /**
     * Turn the given UTF8 byte array into a string
     *
     * @param bytes The byte array
     * @return The string
     */
    public static String utf8(byte[] bytes) {
        try {
            return new String(bytes, "UTF8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("This shouldn't happen.", e);
        }
    }

    /**
     * Turn a string into a utf8 byte[]
     *
     * @param string The string
     * @return The byte[]
     */
    public static byte[] utf8(String string) {
        try {
            return string.getBytes("UTF8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("This shouldn't happen.", e);
        }
    }

    /**
     * Read an unsigned integer from the current position in the buffer, incrementing the position by 4 bytes
     *
     * @param buffer The buffer to read from
     * @return The integer read, as a long to avoid signedness
     */
    public static long readUnsignedInt(ByteBuffer buffer) {
        return buffer.getInt() & 0xffffffffL;
    }

    /**
     * Read an unsigned integer from the given position without modifying the buffers position
     *
     * @param buffer the buffer to read from
     * @param index the index from which to read the integer
     * @return The integer read, as a long to avoid signedness
     */
    public static long readUnsignedInt(ByteBuffer buffer, int index) {
        return buffer.getInt(index) & 0xffffffffL;
    }

    /**
     * Read an unsigned integer stored in little-endian format from the {@link InputStream}.
     *
     * @param in The stream to read from
     * @return The integer read (MUST BE TREATED WITH SPECIAL CARE TO AVOID SIGNEDNESS)
     */
    public static int readUnsignedIntLE(InputStream in) throws IOException {
        return (in.read() << 8 * 0)
             | (in.read() << 8 * 1)
             | (in.read() << 8 * 2)
             | (in.read() << 8 * 3);
    }

    /**
     * Get the little-endian value of an integer as a byte array.
     * @param val The value to convert to a little-endian array
     * @return The little-endian encoded array of bytes for the value
     */
    public static byte[] toArrayLE(int val) {
        return new byte[] {
            (byte) (val >> 8 * 0),
            (byte) (val >> 8 * 1),
            (byte) (val >> 8 * 2),
            (byte) (val >> 8 * 3)
        };
    }

    /**
     * Read an unsigned integer stored in little-endian format from a byte array
     * at a given offset.
     *
     * @param buffer The byte array to read from
     * @param offset The position in buffer to read from
     * @return The integer read (MUST BE TREATED WITH SPECIAL CARE TO AVOID SIGNEDNESS)
     */
    public static int readUnsignedIntLE(byte[] buffer, int offset) {
        return (buffer[offset++] << 8 * 0)
             | (buffer[offset++] << 8 * 1)
             | (buffer[offset++] << 8 * 2)
             | (buffer[offset]   << 8 * 3);
    }

    /**
     * Read an unsigned integer stored in variable-length format from
     * <a href="http://code.google.com/apis/protocolbuffers/docs/encoding.html">
     * Google Protocol Buffers</a>. Also update the index to indicate how many bytes
     * were used to encode this integer.
     *
     * @param in The input to read from
     * @return The integer read (MUST BE TREATED WITH SPECIAL CARE TO AVOID SIGNEDNESS)
     *
     * @throws IllegalArgumentException if variable-length value does not terminate
     *                                  after 5 bytes have been read
     * @throws IOException              if {@link DataInput} throws {@link IOException}
     */
    public static int readUnsignedVarInt(DataInput in) throws IOException {
        int value = 0;
        int i = 0;
        int b;
        while (((b = in.readByte()) & 0x80) != 0) {
            value |= (b & 0x7f) << i;
            i += 7;
            if (i > 35) {
                throw new IllegalArgumentException("Variable length quantity is too long");
            }
        }

        return value | (b << i);
    }

    /**
     * Read an unsigned integer stored in variable-length format from
     * <a href="http://code.google.com/apis/protocolbuffers/docs/encoding.html">
     * Google Protocol Buffers</a>. Also update the index to indicate how many bytes
     * were used to encode this integer.
     *
     * @param buffer The buffer to read from
     * @return The integer read (MUST BE TREATED WITH SPECIAL CARE TO AVOID SIGNEDNESS)
     *
     * @throws IllegalArgumentException if variable-length value does not terminate
     *                                  after 5 bytes have been read
     */
    public static int readUnsignedVarInt(ByteBuffer buffer) {
        int value = 0;
        int i = 0;
        int b;
        while (((b = buffer.get()) & 0x80) != 0) {
            value |= (b & 0x7f) << i;
            i += 7;
            if (i > 35) {
                throw new IllegalArgumentException("Variable length quantity is too long");
            }
        }

        return value | (b << i);
    }

    /**
     * Read an unsigned long stored in variable-length format from
     * <a href="http://code.google.com/apis/protocolbuffers/docs/encoding.html">
     * Google Protocol Buffers</a>. Also update the index to indicate how many bytes
     * were used to encode this long.
     *
     * @param in The input to read from
     * @return The integer read (MUST BE TREATED WITH SPECIAL CARE TO AVOID SIGNEDNESS)
     *
     * @throws IllegalArgumentException if variable-length value does not terminate
     *                                  after 5 bytes have been read
     * @throws IOException              if {@link DataInput} throws {@link IOException}
     */
    public static long readUnsignedVarLong(DataInput in) throws IOException {
        long value = 0L;
        int i = 0;
        long b;
        while (((b = in.readByte()) & 0x80L) != 0) {
            value |= (b & 0x7f) << i;
            i += 7;
            if (i > 63) {
                throw new IllegalArgumentException("Variable length quantity is too long");
            }
        }
        return value | (b << i);
    }

    /**
     * Read an unsigned long stored in variable-length format from
     * <a href="http://code.google.com/apis/protocolbuffers/docs/encoding.html">
     * Google Protocol Buffers</a>. Also update the index to indicate how many bytes
     * were used to encode this long.
     *
     * @param buffer The buffer to read from
     * @return The integer read (MUST BE TREATED WITH SPECIAL CARE TO AVOID SIGNEDNESS)
     *
     * @throws IllegalArgumentException if variable-length value does not terminate
     *                                  after 5 bytes have been read
     */
    public static long readUnsignedVarLong(ByteBuffer buffer) {
        long value = 0L;
        int i = 0;
        long b;
        while (((b = buffer.get()) & 0x80L) != 0) {
            value |= (b & 0x7f) << i;
            i += 7;
            if (i > 63) {
                throw new IllegalArgumentException("Variable length quantity is too long");
            }
        }
        return value | (b << i);
    }

    /**
     * Read an integer stored in variable-length format using Zig-zag decoding from
     * <a href="http://code.google.com/apis/protocolbuffers/docs/encoding.html">.
     * Google Protocol Buffers</a>. Also update the index to indicate how many bytes
     * were used to encode this integer.
     *
     * @param in The input to read from
     * @return The integer read (MUST BE TREATED WITH SPECIAL CARE TO AVOID SIGNEDNESS)
     *
     * @throws IllegalArgumentException if variable-length value does not terminate
     *                                  after 5 bytes have been read
     * @throws IOException              if {@link DataInput} throws {@link IOException}
     */
    public static int readVarInt(DataInput in) throws IOException {
        int raw = readUnsignedVarInt(in);
        // This undoes the trick in writeSignedVarInt()
        int temp = (((raw << 31) >> 31) ^ raw) >> 1;
        // This extra step lets us deal with the largest signed values by treating
        // negative results from read unsigned methods as like unsigned values.
        // Must re-flip the top bit if the original read value had it set.
        return temp ^ (raw & (1 << 31));
    }

    /**
     * Read a long stored in variable-length format using Zig-zag decoding from
     * <a href="http://code.google.com/apis/protocolbuffers/docs/encoding.html">
     * Google Protocol Buffers</a>. Also update the index to indicate how many bytes
     * were used to encode this long.
     *
     * @param in The input to read from
     * @return The integer read (MUST BE TREATED WITH SPECIAL CARE TO AVOID SIGNEDNESS)
     *
     * @throws IllegalArgumentException if variable-length value does not terminate
     *                                  after 5 bytes have been read
     * @throws IOException              if {@link DataInput} throws {@link IOException}
     */
    public static long readVarLong(DataInput in) throws IOException {
        long raw = readUnsignedVarLong(in);
        // This undoes the trick in writeSignedVarLong()
        long temp = (((raw << 63) >> 63) ^ raw) >> 1;
        // This extra step lets us deal with the largest signed values by treating
        // negative results from read unsigned methods as like unsigned values
        // Must re-flip the top bit if the original read value had it set.
        return temp ^ (raw & (1L << 63));
    }

    /**
     * Read a long stored in variable-length format using Zig-zag decoding from
     * <a href="http://code.google.com/apis/protocolbuffers/docs/encoding.html">
     * Google Protocol Buffers</a>. Also update the index to indicate how many bytes
     * were used to encode this long.
     *
     * @param buffer The buffer to read from
     * @return The integer read (MUST BE TREATED WITH SPECIAL CARE TO AVOID SIGNEDNESS)
     *
     * @throws IllegalArgumentException if variable-length value does not terminate
     *                                  after 5 bytes have been read
     */
    public static long readVarLong(ByteBuffer buffer)  {
        long raw = readUnsignedVarLong(buffer);
        // This undoes the trick in writeSignedVarLong()
        long temp = (((raw << 63) >> 63) ^ raw) >> 1;
        // This extra step lets us deal with the largest signed values by treating
        // negative results from read unsigned methods as like unsigned values
        // Must re-flip the top bit if the original read value had it set.
        return temp ^ (raw & (1L << 63));
    }

    /**
     * Write the given long value as a 4 byte unsigned integer. Overflow is ignored.
     *
     * @param buffer The buffer to write to
     * @param value The value to write
     */
    public static void writeUnsignedInt(ByteBuffer buffer, long value) {
        buffer.putInt((int) (value & 0xffffffffL));
    }

    /**
     * Write the given long value as a 4 byte unsigned integer. Overflow is ignored.
     *
     * @param buffer The buffer to write to
     * @param index The position in the buffer at which to begin writing
     * @param value The value to write
     */
    public static void writeUnsignedInt(ByteBuffer buffer, int index, long value) {
        buffer.putInt(index, (int) (value & 0xffffffffL));
    }

    /**
     * Write an unsigned integer in little-endian format to the {@link OutputStream}.
     *
     * @param out The stream to write to
     * @param value The value to write
     */
    public static void writeUnsignedIntLE(OutputStream out, int value) throws IOException {
        out.write(value >>> 8 * 0);
        out.write(value >>> 8 * 1);
        out.write(value >>> 8 * 2);
        out.write(value >>> 8 * 3);
    }

    /**
     * Write an unsigned integer in little-endian format to a byte array
     * at a given offset.
     *
     * @param buffer The byte array to write to
     * @param offset The position in buffer to write to
     * @param value The value to write
     */
    public static void writeUnsignedIntLE(byte[] buffer, int offset, int value) {
        buffer[offset++] = (byte) (value >>> 8 * 0);
        buffer[offset++] = (byte) (value >>> 8 * 1);
        buffer[offset++] = (byte) (value >>> 8 * 2);
        buffer[offset]   = (byte) (value >>> 8 * 3);
    }

    /**
     * Write the given unsigned integer following the variable-length from
     * <a href="http://code.google.com/apis/protocolbuffers/docs/encoding.html">
     * Google Protocol Buffers</a> into the output. Since it the value is not negative Zig-zag is not used.
     *
     * @param value The value to write
     * @param out The output to write to
     */
    public static void writeUnsignedVarInt(int value, DataOutput out) throws IOException {
        while ((value & 0xffffff80) != 0L) {
            out.writeByte((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7f);
    }

    /**
     * Write the given unsigned integer following the variable-length from
     * <a href="http://code.google.com/apis/protocolbuffers/docs/encoding.html">
     * Google Protocol Buffers</a> into the output. Since it the value is not negative Zig-zag is not used.
     *
     * @param value The value to write
     * @param buffer The buffer to write to
     */
    public static void writeUnsignedVarInt(int value, ByteBuffer buffer) {
        while ((value & 0xffffff80) != 0L) {
            byte b = (byte) ((value & 0x7f) | 0x80);
            buffer.put(b);
            value >>>= 7;
        }
        buffer.put((byte) (value & 0x7f));
    }

    /**
     * Write the given unsigned long following the variable-length from
     * <a href="http://code.google.com/apis/protocolbuffers/docs/encoding.html">
     * Google Protocol Buffers</a> into the output. Since it the value is not negative Zig-zag is not used.
     *
     * @param value The value to write
     * @param out The output to write to
     */
    public static void writeUnsignedVarLong(long value, DataOutput out) throws IOException {
        while ((value & 0xffffffffffffff80L) != 0L) {
            out.writeByte(((int) value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.writeByte((int) value & 0x7f);
    }

    /**
     * Write the given unsigned long following the variable-length from
     * <a href="http://code.google.com/apis/protocolbuffers/docs/encoding.html">
     * Google Protocol Buffers</a> into the output. Since it the value is not negative Zig-zag is not used.
     *
     * @param value The value to write
     * @param buffer The buffer to write to
     */
    public static void writeUnsignedVarLong(long value, ByteBuffer buffer) {
        while ((value & 0xffffffffffffff80L) != 0L) {
            byte b = (byte) (((int) value & 0x7f) | 0x80);
            buffer.put(b);
            value >>>= 7;
        }
        buffer.put((byte) ((int) value & 0x7f));
    }

    /**
     * Write the given integer following the variable-length from
     * <a href="http://code.google.com/apis/protocolbuffers/docs/encoding.html">
     * Google Protocol Buffers</a> into the output. Zig-zag encoding is not used.
     *
     * @param value The value to write
     * @param out The output to write to
     */
    public static void writeVarInt(int value, DataOutput out) throws IOException {
        writeUnsignedVarInt((value << 1) ^ (value >> 31), out);
    }

    /**
     * Write the given integer following the variable-length from
     * <a href="http://code.google.com/apis/protocolbuffers/docs/encoding.html">
     * Google Protocol Buffers</a> into the output. Zig-zag encoding is not used.
     *
     * @param value The value to write
     * @param out The output to write to
     */
    public static void writeVarLong(long value, DataOutput out) throws IOException {
        writeUnsignedVarLong((value << 1) ^ (value >> 63), out);
    }

    /**
     * Write the given integer following the variable-length from
     * <a href="http://code.google.com/apis/protocolbuffers/docs/encoding.html">
     * Google Protocol Buffers</a> into the output. Zig-zag encoding is not used.
     *
     * @param value The value to write
     * @param buffer The buffer to write to
     */
    public static void writeVarLong(long value, ByteBuffer buffer) {
        writeUnsignedVarLong((value << 1) ^ (value >> 63), buffer);
    }

    /**
     * Number of bytes needed to encode an unsigned integer in variable-length format.
     *
     * @param value The unsigned integer
     */
    public static int bytesForUnsignedVarIntEncoding(int value) {
        int bytes = 1;
        while ((value & 0xffffff80) != 0L) {
            bytes += 1;
            value >>>= 7;
        }

        return bytes;
    }

    /**
     * Number of bytes needed to encode an unsigned long in variable-length format.
     *
     * @param value The unsigned integer
     */
    public static int bytesForUnsignedVarLongEncoding(long value) {
        int bytes = 1;
        while ((value & 0xffffffffffffff80L) != 0L) {
            bytes += 1;
            value >>>= 7;
        }

        return bytes;
    }

    /**
     * Number of bytes needed to encode an integer in variable-length format.
     *
     * @param value The unsigned integer
     */
    public static int bytesForVarIntEncoding(int value) {
        return bytesForUnsignedVarIntEncoding((value << 1) ^ (value >> 31));
    }

    /**
     * Number of bytes needed to encode an integer in variable-length format.
     *
     * @param value The unsigned integer
     */
    public static int bytesForVarLongEncoding(long value) {
        return bytesForUnsignedVarLongEncoding((value << 1) ^ (value >> 63));
    }

    /**
     * Get the absolute value of the given number. If the number is Int.MinValue return 0. This is different from
     * java.lang.Math.abs or scala.math.abs in that they return Int.MinValue (!).
     */
    public static int abs(int n) {
        return (n == Integer.MIN_VALUE) ? 0 : Math.abs(n);
    }

    /**
     * Get the minimum of some long values.
     * @param first Used to ensure at least one value
     * @param rest The rest of longs to compare
     * @return The minimum of all passed argument.
     */
    public static long min(long first, long ... rest) {
        long min = first;
        for (long r : rest) {
            if (r < min)
                min = r;
        }
        return min;
    }

    public static short min(short first, short second) {
        return (short) Math.min(first, second);
    }

    /**
     * Get the length for UTF8-encoding a string without encoding it first
     *
     * @param s The string to calculate the length for
     * @return The length when serialized
     */
    public static int utf8Length(CharSequence s) {
        int count = 0;
        for (int i = 0, len = s.length(); i < len; i++) {
            char ch = s.charAt(i);
            if (ch <= 0x7F) {
                count++;
            } else if (ch <= 0x7FF) {
                count += 2;
            } else if (Character.isHighSurrogate(ch)) {
                count += 4;
                ++i;
            } else {
                count += 3;
            }
        }
        return count;
    }

    /**
     * Read the given byte buffer into a byte array
     */
    public static byte[] toArray(ByteBuffer buffer) {
        return toArray(buffer, 0, buffer.limit());
    }

    /**
     * Convert a ByteBuffer to a nullable array.
     * @param buffer The buffer to convert
     * @return The resulting array or null if the buffer is null
     */
    public static byte[] toNullableArray(ByteBuffer buffer) {
        return buffer == null ? null : toArray(buffer);
    }

    /**
     * Wrap an array as a nullable ByteBuffer.
     * @param array The nullable array to wrap
     * @return The wrapping ByteBuffer or null if array is null
     */
    public static ByteBuffer wrapNullable(byte[] array) {
        return array == null ? null : ByteBuffer.wrap(array);
    }

    /**
     * Read a byte array from the given offset and size in the buffer
     */
    public static byte[] toArray(ByteBuffer buffer, int offset, int size) {
        byte[] dest = new byte[size];
        if (buffer.hasArray()) {
            System.arraycopy(buffer.array(), buffer.position() + buffer.arrayOffset() + offset, dest, 0, size);
        } else {
            int pos = buffer.position();
            buffer.get(dest);
            buffer.position(pos);
        }
        return dest;
    }

    /**
     * Check that the parameter t is not null
     *
     * @param t The object to check
     * @return t if it isn't null
     * @throws NullPointerException if t is null.
     */
    public static <T> T notNull(T t) {
        if (t == null)
            throw new NullPointerException();
        else
            return t;
    }

    /**
     * Sleep for a bit
     * @param ms The duration of the sleep
     */
    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // this is okay, we just wake up early
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Instantiate the class
     */
    public static <T> T newInstance(Class<T> c) {
        try {
            return c.newInstance();
        } catch (IllegalAccessException e) {
            throw new KafkaException("Could not instantiate class " + c.getName(), e);
        } catch (InstantiationException e) {
            throw new KafkaException("Could not instantiate class " + c.getName() + " Does it have a public no-argument constructor?", e);
        } catch (NullPointerException e) {
            throw new KafkaException("Requested class was null", e);
        }
    }

    /**
     * Look up the class by name and instantiate it.
     * @param klass class name
     * @param base super class of the class to be instantiated
     * @param <T>
     * @return the new instance
     */
    public static <T> T newInstance(String klass, Class<T> base) throws ClassNotFoundException {
        return Utils.newInstance(Class.forName(klass, true, Utils.getContextOrKafkaClassLoader()).asSubclass(base));
    }

    /**
     * Generates 32 bit murmur2 hash from byte array
     * @param data byte array to hash
     * @return 32 bit hash of the given array
     */
    public static int murmur2(final byte[] data) {
        int length = data.length;
        int seed = 0x9747b28c;
        // 'm' and 'r' are mixing constants generated offline.
        // They're not really 'magic', they just happen to work well.
        final int m = 0x5bd1e995;
        final int r = 24;

        // Initialize the hash to a random value
        int h = seed ^ length;
        int length4 = length / 4;

        for (int i = 0; i < length4; i++) {
            final int i4 = i * 4;
            int k = (data[i4 + 0] & 0xff) + ((data[i4 + 1] & 0xff) << 8) + ((data[i4 + 2] & 0xff) << 16) + ((data[i4 + 3] & 0xff) << 24);
            k *= m;
            k ^= k >>> r;
            k *= m;
            h *= m;
            h ^= k;
        }

        // Handle the last few bytes of the input array
        switch (length % 4) {
            case 3:
                h ^= (data[(length & ~3) + 2] & 0xff) << 16;
            case 2:
                h ^= (data[(length & ~3) + 1] & 0xff) << 8;
            case 1:
                h ^= data[length & ~3] & 0xff;
                h *= m;
        }

        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;

        return h;
    }

    /**
     * Extracts the hostname from a "host:port" address string.
     * @param address address string to parse
     * @return hostname or null if the given address is incorrect
     */
    public static String getHost(String address) {
        Matcher matcher = HOST_PORT_PATTERN.matcher(address);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * Extracts the port number from a "host:port" address string.
     * @param address address string to parse
     * @return port number or null if the given address is incorrect
     */
    public static Integer getPort(String address) {
        Matcher matcher = HOST_PORT_PATTERN.matcher(address);
        return matcher.matches() ? Integer.parseInt(matcher.group(2)) : null;
    }

    /**
     * Formats hostname and port number as a "host:port" address string,
     * surrounding IPv6 addresses with braces '[', ']'
     * @param host hostname
     * @param port port number
     * @return address string
     */
    public static String formatAddress(String host, Integer port) {
        return host.contains(":")
                ? "[" + host + "]:" + port // IPv6
                : host + ":" + port;
    }

    /**
     * Create a string representation of an array joined by the given separator
     * @param strs The array of items
     * @param seperator The separator
     * @return The string representation.
     */
    public static <T> String join(T[] strs, String seperator) {
        return join(Arrays.asList(strs), seperator);
    }

    /**
     * Create a string representation of a list joined by the given separator
     * @param list The list of items
     * @param seperator The separator
     * @return The string representation.
     */
    public static <T> String join(Collection<T> list, String seperator) {
        StringBuilder sb = new StringBuilder();
        Iterator<T> iter = list.iterator();
        while (iter.hasNext()) {
            sb.append(iter.next());
            if (iter.hasNext())
                sb.append(seperator);
        }
        return sb.toString();
    }

    public static <K, V> String mkString(Map<K, V> map) {
        return mkString(map, "{", "}", "=", " ,");
    }

    public static <K, V> String mkString(Map<K, V> map, String begin, String end,
                                         String keyValueSeparator, String elementSeperator) {
        StringBuilder bld = new StringBuilder();
        bld.append(begin);
        String prefix = "";
        for (Map.Entry<K, V> entry : map.entrySet()) {
            bld.append(prefix).append(entry.getKey()).
                    append(keyValueSeparator).append(entry.getValue());
            prefix = elementSeperator;
        }
        bld.append(end);
        return bld.toString();
    }

    /**
     * Read a properties file from the given path
     * @param filename The path of the file to read
     */
    public static Properties loadProps(String filename) throws IOException, FileNotFoundException {
        Properties props = new Properties();
        try (InputStream propStream = new FileInputStream(filename)) {
            props.load(propStream);
        }
        return props;
    }

    /**
     * Converts a Properties object to a Map<String, String>, calling {@link #toString} to ensure all keys and values
     * are Strings.
     */
    public static Map<String, String> propsToStringMap(Properties props) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<Object, Object> entry : props.entrySet())
            result.put(entry.getKey().toString(), entry.getValue().toString());
        return result;
    }

    /**
     * Get the stack trace from an exception as a string
     */
    public static String stackTrace(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * Create a new thread
     * @param name The name of the thread
     * @param runnable The work for the thread to do
     * @param daemon Should the thread block JVM shutdown?
     * @return The unstarted thread
     */
    public static Thread newThread(String name, Runnable runnable, boolean daemon) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(daemon);
        thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                log.error("Uncaught exception in thread '" + t.getName() + "':", e);
            }
        });
        return thread;
    }

    /**
     * Create a daemon thread
     * @param name The name of the thread
     * @param runnable The runnable to execute in the background
     * @return The unstarted thread
     */
    public static Thread daemonThread(String name, Runnable runnable) {
        return newThread(name, runnable, true);
    }

    /**
     * Print an error message and shutdown the JVM
     * @param message The error message
     */
    public static void croak(String message) {
        System.err.println(message);
        Exit.exit(1);
    }

    /**
     * Read a buffer into a Byte array for the given offset and length
     */
    public static byte[] readBytes(ByteBuffer buffer, int offset, int length) {
        byte[] dest = new byte[length];
        if (buffer.hasArray()) {
            System.arraycopy(buffer.array(), buffer.arrayOffset() + offset, dest, 0, length);
        } else {
            buffer.mark();
            buffer.position(offset);
            buffer.get(dest, 0, length);
            buffer.reset();
        }
        return dest;
    }

    /**
     * Read the given byte buffer into a Byte array
     */
    public static byte[] readBytes(ByteBuffer buffer) {
        return Utils.readBytes(buffer, 0, buffer.limit());
    }

    /**
     * Attempt to read a file as a string
     * @throws IOException
     */
    public static String readFileAsString(String path, Charset charset) throws IOException {
        if (charset == null) charset = Charset.defaultCharset();

        try (FileInputStream stream = new FileInputStream(new File(path))) {
            FileChannel fc = stream.getChannel();
            MappedByteBuffer bb = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
            return charset.decode(bb).toString();
        }

    }

    public static String readFileAsString(String path) throws IOException {
        return Utils.readFileAsString(path, Charset.defaultCharset());
    }

    /**
     * Check if the given ByteBuffer capacity
     * @param existingBuffer ByteBuffer capacity to check
     * @param newLength new length for the ByteBuffer.
     * returns ByteBuffer
     */
    public static ByteBuffer ensureCapacity(ByteBuffer existingBuffer, int newLength) {
        if (newLength > existingBuffer.capacity()) {
            ByteBuffer newBuffer = ByteBuffer.allocate(newLength);
            existingBuffer.flip();
            newBuffer.put(existingBuffer);
            return newBuffer;
        }
        return existingBuffer;
    }

    /*
     * Creates a set
     * @param elems the elements
     * @param <T> the type of element
     * @return Set
     */
    @SafeVarargs
    public static <T> Set<T> mkSet(T... elems) {
        return new HashSet<>(Arrays.asList(elems));
    }

    /*
     * Creates a list
     * @param elems the elements
     * @param <T> the type of element
     * @return List
     */
    @SafeVarargs
    public static <T> List<T> mkList(T... elems) {
        return Arrays.asList(elems);
    }

    /*
     * Create a string from a collection
     * @param coll the collection
     * @param separator the separator
     */
    public static <T> CharSequence mkString(Collection<T> coll, String separator) {
        StringBuilder sb = new StringBuilder();
        Iterator<T> iter = coll.iterator();
        if (iter.hasNext()) {
            sb.append(iter.next().toString());

            while (iter.hasNext()) {
                sb.append(separator);
                sb.append(iter.next().toString());
            }
        }
        return sb;
    }

    /**
     * Recursively delete the given file/directory and any subfiles (if any exist)
     *
     * @param file The root file at which to begin deleting
     */
    public static void delete(File file) {
        if (file == null) {
            return;
        } else if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files)
                    delete(f);
            }
            file.delete();
        } else {
            file.delete();
        }
    }

    /**
     * Returns an empty collection if this list is null
     * @param other
     * @return
     */
    public static <T> List<T> safe(List<T> other) {
        return other == null ? Collections.<T>emptyList() : other;
    }

   /**
    * Get the ClassLoader which loaded Kafka.
    */
    public static ClassLoader getKafkaClassLoader() {
        return Utils.class.getClassLoader();
    }

    /**
     * Get the Context ClassLoader on this thread or, if not present, the ClassLoader that
     * loaded Kafka.
     *
     * This should be used whenever passing a ClassLoader to Class.forName
     */
    public static ClassLoader getContextOrKafkaClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null)
            return getKafkaClassLoader();
        else
            return cl;
    }

    /**
     * Attempts to move source to target atomically and falls back to a non-atomic move if it fails.
     *
     * @throws IOException if both atomic and non-atomic moves fail
     */
    public static void atomicMoveWithFallback(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException outer) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                log.debug("Non-atomic move of " + source + " to " + target + " succeeded after atomic move failed due to "
                        + outer.getMessage());
            } catch (IOException inner) {
                inner.addSuppressed(outer);
                throw inner;
            }
        }
    }

    /**
     * Closes all the provided closeables.
     * @throws IOException if any of the close methods throws an IOException.
     *         The first IOException is thrown with subsequent exceptions
     *         added as suppressed exceptions.
     */
    public static void closeAll(Closeable... closeables) throws IOException {
        IOException exception = null;
        for (Closeable closeable : closeables) {
            try {
                closeable.close();
            } catch (IOException e) {
                if (exception != null)
                    exception.addSuppressed(e);
                else
                    exception = e;
            }
        }
        if (exception != null)
            throw exception;
    }

    /**
     * Closes {@code closeable} and if an exception is thrown, it is logged at the WARN level.
     */
    public static void closeQuietly(Closeable closeable, String name) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Throwable t) {
                log.warn("Failed to close " + name, t);
            }
        }
    }

    /**
     * A cheap way to deterministically convert a number to a positive value. When the input is
     * positive, the original value is returned. When the input number is negative, the returned
     * positive value is the original value bit AND against 0x7fffffff which is not its absolutely
     * value.
     *
     * Note: changing this method in the future will possibly cause partition selection not to be
     * compatible with the existing messages already placed on a partition since it is used
     * in producer's {@link org.apache.kafka.clients.producer.internals.DefaultPartitioner}
     *
     * @param number a given number
     * @return a positive number.
     */
    public static int toPositive(int number) {
        return number & 0x7fffffff;
    }

    public static int longHashcode(long value) {
        return (int) (value ^ (value >>> 32));
    }

    /**
     * Read a size-delimited byte buffer starting at the given offset.
     * @param buffer Buffer containing the size and data
     * @param start Offset in the buffer to read from
     * @return A slice of the buffer containing only the delimited data (excluding the size)
     */
    public static ByteBuffer sizeDelimited(ByteBuffer buffer, int start) {
        int size = buffer.getInt(start);
        if (size < 0) {
            return null;
        } else {
            ByteBuffer b = buffer.duplicate();
            b.position(start + 4);
            b = b.slice();
            b.limit(size);
            b.rewind();
            return b;
        }
    }

    /**
     * Compute the checksum of a range of data
     * @param buffer Buffer containing the data to checksum
     * @param start Offset in the buffer to read from
     * @param size The number of bytes to include
     * @return the computed checksum
     */
    public static long computeChecksum(ByteBuffer buffer, int start, int size) {
        return computeChecksum(buffer.array(), buffer.arrayOffset() + start, size);
    }

    /**
     * Compute the checksum of a range of data
     * @param buffer Buffer containing the data to checksum
     * @param start Offset in the buffer to read from
     * @param size The number of bytes to include
     * @return the computed checksum
     */
    public static long computeChecksum(byte[] buffer, int start, int size) {
        Crc32 crc = new Crc32();
        crc.update(buffer, start, size);
        return crc.getValue();
    }

    /**
     * Read data from the channel to the given byte buffer until there are no bytes remaining in the buffer. If the end
     * of the file is reached while there are bytes remaining in the buffer, an EOFException is thrown.
     *
     * @param channel File channel containing the data to read from
     * @param destinationBuffer The buffer into which bytes are to be transferred
     * @param position The file position at which the transfer is to begin; it must be non-negative
     * @param description A description of what is being read, this will be included in the EOFException if it is thrown
     *
     * @throws IllegalArgumentException If position is negative
     * @throws EOFException If the end of the file is reached while there are remaining bytes in the destination buffer
     * @throws IOException If an I/O error occurs, see {@link FileChannel#read(ByteBuffer, long)} for details on the
     * possible exceptions
     */
    public static void readFullyOrFail(FileChannel channel, ByteBuffer destinationBuffer, long position,
                                       String description) throws IOException {
        if (position < 0) {
            throw new IllegalArgumentException("The file channel position cannot be negative, but it is " + position);
        }
        int expectedReadBytes = destinationBuffer.remaining();
        readFully(channel, destinationBuffer, position);
        if (destinationBuffer.hasRemaining()) {
            throw new EOFException(String.format("Failed to read `%s` from file channel `%s`. Expected to read %d bytes, " +
                    "but reached end of file after reading %d bytes. Started read from position %d.",
                    description, channel, expectedReadBytes, expectedReadBytes - destinationBuffer.remaining(), position));
        }
    }

    /**
     * Read data from the channel to the given byte buffer until there are no bytes remaining in the buffer or the end
     * of the file has been reached.
     *
     * @param channel File channel containing the data to read from
     * @param destinationBuffer The buffer into which bytes are to be transferred
     * @param position The file position at which the transfer is to begin; it must be non-negative
     *
     * @throws IllegalArgumentException If position is negative
     * @throws IOException If an I/O error occurs, see {@link FileChannel#read(ByteBuffer, long)} for details on the
     * possible exceptions
     */
    public static void readFully(FileChannel channel, ByteBuffer destinationBuffer, long position) throws IOException {
        if (position < 0) {
            throw new IllegalArgumentException("The file channel position cannot be negative, but it is " + position);
        }
        long currentPosition = position;
        int bytesRead;
        do {
            bytesRead = channel.read(destinationBuffer, currentPosition);
            currentPosition += bytesRead;
        } while (bytesRead != -1 && destinationBuffer.hasRemaining());
    }

    public static <T> List<T> toList(Iterator<T> iterator) {
        List<T> res = new ArrayList<>();
        while (iterator.hasNext())
            res.add(iterator.next());
        return res;
    }

}
