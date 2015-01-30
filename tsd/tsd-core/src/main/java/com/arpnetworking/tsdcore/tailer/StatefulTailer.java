/**
 * Copyright 2014 Groupon.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.arpnetworking.tsdcore.tailer;

import com.arpnetworking.utility.OvalBuilder;
import com.arpnetworking.utility.TimerTrigger;
import com.arpnetworking.utility.Trigger;
import com.google.common.base.Charsets;
import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import net.sf.oval.constraint.NotNull;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * A reimplementation of the Apache Commons IO tailer based on the 2.5 snapshot
 * version. This version attempts to address several shortcomings of the Apache
 * Commons implementation. In particular, more robust support for rename-
 * recreate file rotations and some progress for copy-truncate cases. The major
 * new feature is the <code>PositionStore</code> which is used to checkpoint
 * the offset in the tailed file as identified by a hash of the file prefix.
 *
 * @author Brandon Arp (barp at groupon dot com)
 * @author Ville Koskela (vkoskela at groupon dot com)
 */
public final class StatefulTailer implements Tailer {

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        _isRunning = false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        try {
            fileLoop();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            handleThrowable(e);
            // CHECKSTYLE.OFF: IllegalCatch - Intercept all exceptions
        } catch (final Throwable t) {
            handleThrowable(t);
            // CHECKSTYLE.ON: IllegalCatch
        } finally {
            IOUtils.closeQuietly(_positionStore);
            IOUtils.closeQuietly(_lineBuffer);
        }
    }

    /**
     * Determine if the <code>Tailer</code> is running.
     *
     * @return <code>True</code> if and only if the <code>Tailer</code> is running.
     */
    protected boolean isRunning() {
        return _isRunning;
    }

    private void fileLoop() throws IOException, InterruptedException {
        SeekableByteChannel reader = null;
        InitialPosition nextInitialPosition = _initialPosition;
        try {
            while (isRunning()) {
                // Attempt to open the file
                try {
                    reader = Files.newByteChannel(_file.toPath(), StandardOpenOption.READ);
                    LOGGER.trace(String.format(
                            "Opened file; file=%s",
                            _file));
                } catch (final NoSuchFileException e) {
                    _listener.fileNotFound();
                    _trigger.waitOnTrigger();
                }

                if (reader != null) {
                    // Attempt to resume from checkpoint
                    long position = nextInitialPosition.get(reader);
                    // Any subsequent file opens we should start at the beginning
                    nextInitialPosition = InitialPosition.START;
                    _hash = computeHash(reader, REQUIRED_BYTES_FOR_HASH);
                    if (_hash.isPresent()) {
                        position = _positionStore.getPosition(_hash.get()).or(position).longValue();
                    }
                    LOGGER.trace(String.format(
                            "Starting tail; file=%s, position=%d",
                            _file,
                            Long.valueOf(position)));
                    reader.position(position);

                    // Read the file
                    readLoop(reader);

                    // Reset per file state
                    IOUtils.closeQuietly(reader);
                    reader = null;
                    _hash = Optional.absent();
                }
            }
        } finally {
            IOUtils.closeQuietly(reader);
            reader = null;
            _hash = Optional.absent();
        }
    }

    private void readLoop(final SeekableByteChannel reader) throws IOException, InterruptedException {
        Optional<Long> lastChecked = Optional.absent();
        Optional<String> currentReaderPrefixHash = Optional.absent();
        int currentReaderPrefixHashLength = 0;
        while (isRunning()) {
            // Obtain properties of file we expect we are reading
            final Attributes attributes;
            try {
                attributes = getAttributes(_file, lastChecked);
            } catch (final NoSuchFileException t) {
                rotate(Optional.of(reader), String.format(
                        "File rotation detected based attributes access failure; file=%s",
                        _file));

                // Return to the file loop
                return;
            }

            if (attributes.getLength() < reader.position()) {
                // File was rotated; either:
                // 1) Position is past the length of the file
                // 2) The expected file is smaller than the current file
                rotate(Optional.of(reader), String.format(
                        "File rotation detected based on length, position and size; file=%s, length=%d, position=%d, size=%d",
                        _file,
                        Long.valueOf(attributes.getLength()),
                        Long.valueOf(reader.position()),
                        Long.valueOf(reader.size())));

                // Return to the file loop
                return;

            } else {
                // File was _likely_ not rotated
                if (reader.size() > reader.position()) {
                    // There is more data in the file
                    if (!readLines(reader)) {
                        // There actually isn't any more data in the file; this
                        // means the file was rotated and the new file has more
                        // data than the old file (e.g. rotation from empty).

                        // TODO(vkoskela): Account for missing final newline. [MAI-322]
                        // There is a degenerate case where the last line in a
                        // file does not have a newline. Then readLines will
                        // always find new data, but the file has been rotated
                        // away. We should buffer the contents of partial lines
                        // thereby detecting when the length grows whether we
                        // actually got more data in the current file.

                        rotate(Optional.<SeekableByteChannel>absent(), String.format(
                                "File rotation detected based on length and no new data; file=%s, length=%d, position=%d",
                                _file,
                                Long.valueOf(attributes.getLength()),
                                Long.valueOf(reader.position())));

                        // Return to the file loop
                        return;
                    }
                    lastChecked = Optional.of(Long.valueOf(_file.lastModified()));

                } else if (attributes.isNewer()) {
                    // The file does not contain any additional data, but its
                    // last modified date is after the last read date. The file
                    // must have rotated and contains the same length of
                    // content. This can happen on periodic systems which log
                    // the same data at the beginning of each period.

                    rotate(Optional.<SeekableByteChannel>absent(), String.format(
                            "File rotation detected based equal length and position but newer"
                                    + "; file=%s, length=%d, position=%d, lastChecked=%s, attributes=%s",
                            _file,
                            Long.valueOf(attributes.getLength()),
                            Long.valueOf(reader.position()),
                            lastChecked.get(),
                            attributes));

                    // Return to the file loop
                    return;

                } else {
                    // The files are the same size and the timestamps are the
                    // same. This is more common than it sounds since file
                    // modification timestamps are not very precise on many
                    // file systems.
                    //
                    // Since we're not doing anything at this point let's hash
                    // the first N bytes of the current file and the expected
                    // file to see if we're still working on the same file.

                    final Optional<Boolean> hashesSame = compareByHash(currentReaderPrefixHash, currentReaderPrefixHashLength);
                    if (hashesSame.isPresent() && !hashesSame.get().booleanValue()) {
                        // The file rotated with the same length!
                        rotate(Optional.<SeekableByteChannel>absent(), String.format(
                                "File rotation detected based on hash; file=%s",
                                _file));

                        // Return to the file loop
                        return;
                    }
                    // else: the files are empty or the hashes are the same. In
                    // either case we don't have enough data to determine if
                    // the files are different; we'll need to wait and see when
                    // more data is written if the size and length diverge.

                    // TODO(vkoskela): Configurable maximum rotation hash size. [MAI-323]
                    // TODO(vkoskela): Configurable minimum rotation hash size. [MAI-324]
                    // TODO(vkoskela): Configurable identity hash size. [MAI-325]
                    // TODO(vkoskela): We should add a rehash interval. [MAI-326]
                    // This interval would be separate from the read interval,
                    // and generally longer, preventing us from rehashing the
                    // file every interval; but short enough that we don't wait
                    // too long before realizing a slowly growing file was
                    // rotated.
                }
            }

            // Compute the prefix hash unless we have an identity
            if (!_hash.isPresent()) {
                currentReaderPrefixHashLength = (int) Math.min(reader.size(), REQUIRED_BYTES_FOR_HASH);
                currentReaderPrefixHash = computeHash(reader, currentReaderPrefixHashLength);
            }

            // Read interval
            _trigger.waitOnTrigger();

            // Update the reader position
            updateCheckpoint(reader.position());
        }
    }

    private Attributes getAttributes(final File file, final Optional<Long> lastChecked) throws IOException {
        final BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
        LOGGER.trace(String.format(
                "File attributes; file=%s, lastModifiedTime=%d, size=%d",
                file,
                Long.valueOf(attributes.lastModifiedTime().toMillis()),
                Long.valueOf(attributes.size())));

        return new Attributes(
                attributes.size(),
                attributes.lastModifiedTime().toMillis(),
                lastChecked.isPresent() && attributes.lastModifiedTime().toMillis() > lastChecked.get().longValue());
    }

    private void rotate(final Optional<SeekableByteChannel> reader, final String reason) throws InterruptedException, IOException {
        // Allow a full read interval before calling it quits on the old file
        if (reader.isPresent()) {
            _trigger.waitOnTrigger();
            readLines(reader.get());
        }

        // Inform the listener
        _listener.fileRotated();

        LOGGER.trace(reason);
    }

    private boolean readLines(final SeekableByteChannel reader) throws IOException {
        // Compute the hash if not already set
        if (!_hash.isPresent() && reader.size() >= REQUIRED_BYTES_FOR_HASH) {
            _hash = computeHash(reader, REQUIRED_BYTES_FOR_HASH);
        }

        // Track current position in file and next read position
        // NOTE: The next read position is always the beginning of a line
        long position = reader.position();
        long nextReadPosition = position;

        // Reset buffers
        _buffer.clear();
        _lineBuffer.reset();

        // Process available data
        int bufferSize = reader.read(_buffer);
        boolean hasData = false;
        boolean hasCR = false;
        while (isRunning() && bufferSize != -1) {
            hasData = true;
            for (int i = 0; i < bufferSize; i++) {
                final byte ch = _buffer.get(i);
                switch (ch) {
                    case '\n':
                        hasCR = false;
                        handleLine();
                        nextReadPosition = position + i + 1;
                        updateCheckpoint(nextReadPosition);
                        break;
                    case '\r':
                        if (hasCR) {
                            _lineBuffer.write('\r');
                        }
                        hasCR = true;
                        break;
                    default:
                        if (hasCR) {
                            hasCR = false;
                            handleLine();
                            nextReadPosition = position + i + 1;
                            updateCheckpoint(nextReadPosition);
                        }
                        _lineBuffer.write(ch);
                }
            }
            position = reader.position();
            _buffer.clear();
            bufferSize = reader.read(_buffer);
        }

        reader.position(nextReadPosition);
        return hasData;
    }

    private Optional<Boolean> compareByHash(final Optional<String> prefixHash, final int prefixLength) {
        final int appliedLength;
        if (_hash.isPresent()) {
            appliedLength = REQUIRED_BYTES_FOR_HASH;
        } else {
            appliedLength = prefixLength;
        }
        try (final SeekableByteChannel reader = Files.newByteChannel(_file.toPath(), StandardOpenOption.READ)) {
            final Optional<String> filePrefixHash = computeHash(
                    reader,
                    appliedLength);

            LOGGER.trace(String.format(
                    "Comparing hashes; hash1=%s, hash2=%s, size=%d",
                    prefixHash,
                    filePrefixHash,
                    Integer.valueOf(appliedLength)));

            return Optional.of(Boolean.valueOf(Objects.equals(_hash.or(prefixHash).orNull(), filePrefixHash.orNull())));
        } catch (final IOException e) {
            return Optional.absent();
        }
    }

    private Optional<String> computeHash(final SeekableByteChannel reader, final int hashSize) throws IOException {
        // Don't hash empty data sets
        if (hashSize <= 0) {
            return Optional.absent();
        }

        // Validate sufficient data to compute the hash
        final long oldPosition = reader.position();
        reader.position(0);
        if (reader.size() < hashSize) {
            reader.position(oldPosition);
            LOGGER.trace(String.format(
                    "Reader size insufficient to compute hash; hashSize=%s, hashSize=%d",
                    Integer.valueOf(hashSize),
                    Long.valueOf(reader.size())));
            return Optional.absent();
        }

        // Read the data to hash
        final ByteBuffer buffer = ByteBuffer.allocate(hashSize);
        int totalBytesRead = 0;
        while (totalBytesRead < hashSize) {
            final int bytesRead = reader.read(buffer);
            if (bytesRead < 0) {
                LOGGER.warn(String.format(
                        "Unexpected end of file reached; totalBytesRead=%d",
                        Long.valueOf(totalBytesRead)));
                return Optional.absent();
            }
            totalBytesRead += bytesRead;
        }

        // Compute the hash
        _md5.reset();
        final byte[] digest = _md5.digest(buffer.array());
        final String hash = Hex.encodeHexString(digest);
        LOGGER.trace(String.format("Computed hash; hash=%s, bytes=%s", hash, Hex.encodeHexString(buffer.array())));

        // Return the reader to its original state
        reader.position(oldPosition);
        return Optional.of(hash);
    }

    private void updateCheckpoint(final long position) {
        if (_hash.isPresent()) {
            _positionStore.setPosition(_hash.get(), position);
        }
    }

    private void handleLine() {
        //CHECKSTYLE.OFF: IllegalInstantiation - This is how you convert a byte[] to String.
        LOGGER.trace("handleLine: " + new String(_lineBuffer.toByteArray(), _characterSet));
        _listener.handle(new String(_lineBuffer.toByteArray(), _characterSet));
        _lineBuffer.reset();
        //CHECKSTYLE.ON: IllegalInstantiation
    }

    private void handleThrowable(final Throwable t) {
        _listener.handle(t);
        stop();
    }

    // NOTE: Package private for testing

    /* package private */StatefulTailer(final Builder builder, final Trigger trigger) {
        _file = builder._file;
        _positionStore = builder._positionStore;
        _listener = builder._listener;
        _trigger = trigger;

        _buffer = ByteBuffer.allocate(INITIAL_BUFFER_SIZE);
        _lineBuffer = new ByteArrayOutputStream(INITIAL_BUFFER_SIZE);
        try {
            _md5 = MessageDigest.getInstance("MD5");
        } catch (final NoSuchAlgorithmException e) {
            throw Throwables.propagate(e);
        }

        _initialPosition = builder._initialPosition;
        _listener.initialize(this);
    }

    private StatefulTailer(final Builder builder) {
        // TODO(vkoskela): Configurable grace period separate from interval. [MAI-327]
        this(builder, new TimerTrigger(builder._readInterval));
    }

    private final File _file;
    private final PositionStore _positionStore;
    private final TailerListener _listener;
    private final ByteBuffer _buffer;
    private final ByteArrayOutputStream _lineBuffer;
    private final MessageDigest _md5;
    private final Charset _characterSet = Charsets.UTF_8;
    private final InitialPosition _initialPosition;
    private final Trigger _trigger;

    private volatile boolean _isRunning = true;
    private Optional<String> _hash = Optional.absent();

    private static final Long ZERO = Long.valueOf(0);
    private static final int REQUIRED_BYTES_FOR_HASH = 512;
    private static final int INITIAL_BUFFER_SIZE = 65536;
    private static final Logger LOGGER = LoggerFactory.getLogger(StatefulTailer.class);

    private static final class Attributes {

        public Attributes(
                final long length,
                final long lastModifiedTime,
                final boolean newer) {
            _length = length;
            _lastModifiedTime = lastModifiedTime;
            _newer = newer;
        }

        public long getLength() {
            return _length;
        }

        public long getLastModifiedTime() {
            return _lastModifiedTime;
        }

        public boolean isNewer() {
            return _newer;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(Attributes.class)
                    .add("Length", _length)
                    .add("LastModifiedTime", _lastModifiedTime)
                    .add("Newer", _newer)
                    .toString();
        }

        private final long _length;
        private final long _lastModifiedTime;
        private final boolean _newer;
    }

    /**
     * Implementation of builder pattern for <code>StatefulTailer</code>.
     *
     * @author Brandon Arp (barp at groupon dot com)
     */
    public static class Builder extends OvalBuilder<StatefulTailer> {

        /**
         * Public constructor.
         */
        public Builder() {
            super(StatefulTailer.class);
        }

        /**
         * Sets the file to read. Cannot be null or empty.
         *
         * @param value The file to read.
         * @return This instance of {@link Builder}
         */
        public Builder setFile(final File value) {
            _file = value;
            return this;
        }

        /**
         * Sets the <code>PositionStore</code> to be used to checkpoint the
         * file read position. Cannot be null.
         *
         * @param value The <code>PositionStore</code> instance.
         * @return This instance of {@link Builder}
         */
        public Builder setPositionStore(final PositionStore value) {
            _positionStore = value;
            return this;
        }

        /**
         * Sets the <code>TailerListener</code> instance. Cannot be null.
         *
         * @param value The <code>TailerListener</code> instance.
         * @return This instance of {@link Builder}
         */
        public Builder setListener(final TailerListener value) {
            _listener = value;
            return this;
        }

        /**
         * Sets the interval between file reads. Optional. Default is 500
         * milliseconds.
         *
         * @param value The file read interval.
         * @return This instance of {@link Builder}
         */
        public Builder setReadInterval(final Duration value) {
            _readInterval = value;
            return this;
        }

        /**
         * Sets the tailer to start at the current end of the file.
         *
         * @param initialPosition The initial position of the tailer
         * @return This instance of {@link Builder}
         */
        public Builder setInitialPosition(final InitialPosition initialPosition) {
            _initialPosition = initialPosition;
            return this;
        }

        @NotNull
        private File _file;
        @NotNull
        private PositionStore _positionStore;
        @NotNull
        private TailerListener _listener;
        @NotNull
        private Duration _readInterval = Duration.millis(500);
        @NotNull
        private InitialPosition _initialPosition = InitialPosition.START;
    }
}
