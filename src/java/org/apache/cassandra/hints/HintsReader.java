/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.hints;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;

import javax.annotation.Nullable;

import com.google.common.util.concurrent.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.UnknownColumnFamilyException;
import org.apache.cassandra.io.FSReadError;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.io.util.RandomAccessReader;
import org.apache.cassandra.utils.AbstractIterator;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.CLibrary;

/**
 * A paged non-compressed hints reader that provides two iterators:
 * - a 'raw' ByteBuffer iterator that doesn't deserialize the hints, but returns the pre-encoded hints verbatim
 * - a decoded iterator, that deserializes the underlying bytes into {@link Hint} instances.
 *
 * The former is an optimisation for when the messaging version of the file matches the messaging version of the destination
 * node. Extra decoding and reencoding is a waste of effort in this scenario, so we avoid it.
 *
 * The latter is required for dispatch of hints to nodes that have a different messaging version, and in general is just an
 * easy way to enable backward and future compatibilty.
 */
final class HintsReader implements AutoCloseable, Iterable<HintsReader.Page>
{
    private static final Logger logger = LoggerFactory.getLogger(HintsReader.class);

    // don't read more than 512 KB of hints at a time.
    private static final int PAGE_SIZE = 512 << 10;

    private final HintsDescriptor descriptor;
    private final File file;
    private final RandomAccessReader reader;
    private final ChecksummedDataInput crcInput;

    // we pass the RateLimiter into HintsReader itself because it's cheaper to calculate the size before the hint is deserialized
    @Nullable
    private final RateLimiter rateLimiter;

    private HintsReader(HintsDescriptor descriptor, File file, RandomAccessReader reader, RateLimiter rateLimiter)
    {
        this.descriptor = descriptor;
        this.file = file;
        this.reader = reader;
        this.crcInput = ChecksummedDataInput.wrap(reader);
        this.rateLimiter = rateLimiter;
    }

    static HintsReader open(File file, RateLimiter rateLimiter)
    {
        RandomAccessReader reader = RandomAccessReader.open(file);
        try
        {
            HintsDescriptor descriptor = HintsDescriptor.deserialize(reader);
            return new HintsReader(descriptor, file, reader, rateLimiter);
        }
        catch (IOException e)
        {
            reader.close();
            throw new FSReadError(e, file);
        }
    }

    static HintsReader open(File file)
    {
        return open(file, null);
    }

    public void close()
    {
        FileUtils.closeQuietly(reader);
    }

    public HintsDescriptor descriptor()
    {
        return descriptor;
    }

    void seek(long newPosition)
    {
        reader.seek(newPosition);
    }

    public Iterator<Page> iterator()
    {
        return new PagesIterator();
    }

    final class Page
    {
        public final long offset;

        private Page(long offset)
        {
            this.offset = offset;
        }

        Iterator<Hint> hintsIterator()
        {
            return new HintsIterator(offset);
        }

        Iterator<ByteBuffer> buffersIterator()
        {
            return new BuffersIterator(offset);
        }
    }

    final class PagesIterator extends AbstractIterator<Page>
    {
        @SuppressWarnings("resource")
        protected Page computeNext()
        {
            CLibrary.trySkipCache(reader.getChannel().getFileDescriptor(), 0, reader.getFilePointer(), reader.getPath());

            if (reader.length() == reader.getFilePointer())
                return endOfData();

            return new Page(reader.getFilePointer());
        }
    }

    /**
     * A decoding iterator that deserializes the hints as it goes.
     */
    final class HintsIterator extends AbstractIterator<Hint>
    {
        private final long offset;

        HintsIterator(long offset)
        {
            super();
            this.offset = offset;
        }

        protected Hint computeNext()
        {
            Hint hint;

            do
            {
                long position = reader.getFilePointer();

                if (reader.length() == position)
                    return endOfData(); // reached EOF

                if (position - offset >= PAGE_SIZE)
                    return endOfData(); // read page size or more bytes

                try
                {
                    hint = computeNextInternal();
                }
                catch (IOException e)
                {
                    throw new FSReadError(e, file);
                }
            }
            while (hint == null);

            return hint;
        }

        private Hint computeNextInternal() throws IOException
        {
            crcInput.resetCrc();
            crcInput.resetLimit();

            int size = crcInput.readInt();

            // if we cannot corroborate the size via crc, then we cannot safely skip this hint
            if (reader.readInt() != crcInput.getCrc())
                throw new IOException("Digest mismatch exception");

            return readHint(size);
        }

        private Hint readHint(int size) throws IOException
        {
            if (rateLimiter != null)
                rateLimiter.acquire(size);
            crcInput.limit(size);

            Hint hint;
            try
            {
                hint = Hint.serializer.deserialize(crcInput, descriptor.messagingVersion());
            }
            catch (UnknownColumnFamilyException e)
            {
                logger.warn("Failed to read a hint for {} - table with id {} is unknown in file {}",
                            descriptor.hostId,
                            e.cfId,
                            descriptor.fileName());
                reader.skipBytes(crcInput.bytesRemaining());

                return null;
            }

            if (reader.readInt() == crcInput.getCrc())
                return hint;

            // log a warning and skip the corrupted entry
            logger.warn("Failed to read a hint for {} - digest mismatch for hint at position {} in file {}",
                        descriptor.hostId,
                        crcInput.getPosition() - size - 4,
                        descriptor.fileName());
            return null;
        }
    }

    /**
     * A verbatim iterator that simply returns the underlying ByteBuffers.
     */
    final class BuffersIterator extends AbstractIterator<ByteBuffer>
    {
        private final long offset;

        BuffersIterator(long offset)
        {
            super();
            this.offset = offset;
        }

        protected ByteBuffer computeNext()
        {
            ByteBuffer buffer;

            do
            {
                long position = reader.getFilePointer();

                if (reader.length() == position)
                    return endOfData(); // reached EOF

                if (position - offset >= PAGE_SIZE)
                    return endOfData(); // read page size or more bytes

                try
                {
                    buffer = computeNextInternal();
                }
                catch (IOException e)
                {
                    throw new FSReadError(e, file);
                }
            }
            while (buffer == null);

            return buffer;
        }

        private ByteBuffer computeNextInternal() throws IOException
        {
            crcInput.resetCrc();
            crcInput.resetLimit();

            int size = crcInput.readInt();

            // if we cannot corroborate the size via crc, then we cannot safely skip this hint
            if (reader.readInt() != crcInput.getCrc())
                throw new IOException("Digest mismatch exception");

            return readBuffer(size);
        }

        private ByteBuffer readBuffer(int size) throws IOException
        {
            if (rateLimiter != null)
                rateLimiter.acquire(size);
            crcInput.limit(size);

            ByteBuffer buffer = ByteBufferUtil.read(crcInput, size);
            if (reader.readInt() == crcInput.getCrc())
                return buffer;

            // log a warning and skip the corrupted entry
            logger.warn("Failed to read a hint for {} - digest mismatch for hint at position {} in file {}",
                        descriptor.hostId,
                        crcInput.getPosition() - size - 4,
                        descriptor.fileName());
            return null;
        }
    }
}
