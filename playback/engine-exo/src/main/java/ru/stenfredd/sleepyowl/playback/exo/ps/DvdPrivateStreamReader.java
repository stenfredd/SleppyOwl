package ru.stenfredd.sleepyowl.playback.exo.ps;

import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ts.Ac3Reader;
import androidx.media3.extractor.ts.ElementaryStreamReader;
import androidx.media3.extractor.ts.TsPayloadReader;

/**
 * Reader for MPEG-PS private stream 1 (0xBD) as DVDs use it: every payload starts with a substream header (id, number
 * of frame headers, 2-byte first access unit pointer), and one stream id carries all AC3 tracks (0x80-0x87), DTS
 * (0x88-0x8F), LPCM (0xA0-0xA7) and subpictures (0x20-0x3F).
 *
 * <p>Each AC3 substream gets its own {@link Ac3Reader} and track, so the usual track selection picks the language;
 * other substreams are dropped. A stream without substream headers (raw AC3 starting with the 0B 77 sync word) goes
 * to a single reader unchanged.
 *
 * <p>Tracks can only be created before {@link DvdPsExtractor} ends the track list; substreams that first show up later
 * are ignored.
 */
@UnstableApi
/* package */ final class DvdPrivateStreamReader implements ElementaryStreamReader {

  private static final int MODE_UNKNOWN = 0;
  private static final int MODE_SUBSTREAMS = 1;
  private static final int MODE_RAW = 2;
  private static final int RAW_KEY = -1;

  private final SparseArray<Ac3Reader> readers = new SparseArray<>();
  @Nullable private ExtractorOutput output;
  private int mode = MODE_UNKNOWN;
  private boolean tracksEnded;
  private long pesTimeUs = C.TIME_UNSET;
  private int pesFlags;
  @Nullable private Ac3Reader current;

  /** Called by {@link DvdPsExtractor} when it ends the track list. */
  public void onTracksEnded() {
    tracksEnded = true;
  }

  @Override
  public void seek() {
    for (int i = 0; i < readers.size(); i++) {
      readers.valueAt(i).seek();
    }
    current = null;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TsPayloadReader.TrackIdGenerator idGenerator) {
    // Tracks are created per substream as they appear.
    output = extractorOutput;
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    this.pesTimeUs = pesTimeUs;
    this.pesFlags = flags;
  }

  @Override
  public void consume(ParsableByteArray data) throws ParserException {
    current = null;
    if (data.bytesLeft() < 4) {
      return;
    }
    byte[] bytes = data.getData();
    int position = data.getPosition();
    if (mode == MODE_UNKNOWN) {
      boolean ac3Sync = (bytes[position] & 0xFF) == 0x0B && (bytes[position + 1] & 0xFF) == 0x77;
      mode = ac3Sync ? MODE_RAW : MODE_SUBSTREAMS;
    }
    int key;
    if (mode == MODE_RAW) {
      key = RAW_KEY;
    } else {
      key = bytes[position] & 0xFF;
      if (key < 0x80 || key > 0x87) {
        return;
      }
    }
    @Nullable Ac3Reader reader = readerFor(key);
    if (reader == null) {
      return;
    }
    if (mode == MODE_SUBSTREAMS) {
      data.skipBytes(4);
    }
    current = reader;
    reader.packetStarted(pesTimeUs, pesFlags);
    reader.consume(data);
  }

  @Override
  public void packetFinished() {
    if (current != null) {
      current.packetFinished();
    }
    current = null;
  }

  @Override
  public void endOfInputReached() {
    for (int i = 0; i < readers.size(); i++) {
      readers.valueAt(i).endOfInputReached();
    }
  }

  @Nullable
  private Ac3Reader readerFor(int key) {
    @Nullable Ac3Reader reader = readers.get(key);
    if (reader != null || tracksEnded || output == null) {
      return reader;
    }
    reader = new Ac3Reader(MimeTypes.VIDEO_PS);
    // Track ids above the PS stream id range (0..0xFF) used by DvdPsExtractor for the other streams.
    int trackId = key == RAW_KEY ? DvdPsExtractor.PRIVATE_STREAM_1 : 0x100 + key;
    reader.createTracks(output, new TsPayloadReader.TrackIdGenerator(trackId, 0x200));
    readers.put(key, reader);
    return reader;
  }
}
