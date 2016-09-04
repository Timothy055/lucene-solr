/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.search.uhighlight;

import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;

/**
 * Internal highlighter abstraction that operates on a per field basis.
 *
 * @lucene.internal
 */
public class FieldHighlighter {

  private final String field;
  private final FieldOffsetStrategy fieldOffsetStrategy;
  private final PassageScorer passageScorer;
  private final PassageFormatter passageFormatter;
  private final BreakIterator breakIterator;
  private final int maxPassages;
  private final int maxNoHighlightPassages;

  public FieldHighlighter(String field, FieldOffsetStrategy fieldOffsetStrategy, PassageScorer passageScorer, PassageFormatter passageFormatter,
                          BreakIterator breakIterator, int maxPassages, int maxNoHighlightPassages) {
    this.field = field;
    this.fieldOffsetStrategy = fieldOffsetStrategy;
    this.passageScorer = passageScorer;
    this.passageFormatter = passageFormatter;
    this.breakIterator = breakIterator;
    this.maxPassages = maxPassages;
    this.maxNoHighlightPassages = maxNoHighlightPassages;
  }

  public String getField() {
    return field;
  }

  public UnifiedHighlighter.OffsetSource getOffsetSource() {
    return fieldOffsetStrategy.getOffsetSource();
  }

  public Object highlightFieldForDoc(IndexReader reader, int docId, String content, int maxPassages) throws IOException {
    // note: it'd be nice to accept a CharSequence for content, but we need a CharacterIterator impl for it.
    if (content.length() == 0) {
      return null; // nothing to do
    }
    BreakIterator breakIterator = this.breakIterator;
    breakIterator.setText(content);

    List<OffsetsEnum> offsetsEnums = fieldOffsetStrategy.getOffsetsEnums(reader, docId, content);

    Passage[] passages;
    try {
      // Highlight the offsetsEnum list against the content to produce Passages.
      passages = highlightOffsetsEnums(offsetsEnums);// and breakIterator & scorer
    } finally {
      // Ensure closeable resources get closed
      IOUtils.close(offsetsEnums);
    }

    // Format the resulting Passages.
    if (passages.length == 0) {
      // no passages were returned, so ask for a default summary
      int maxNoHighlightPassages = this.maxNoHighlightPassages;
      passages = getSummaryPassagesNoHighlight(maxNoHighlightPassages == -1 ? maxPassages : maxNoHighlightPassages);
    }

    if (passages.length > 0) {
      return passageFormatter.format(passages, content);
    } else {
      return null;
    }
  }

  /**
   * Called to summarize a document when no highlights were found.
   * By default this just returns the first
   * {@code maxPassages} sentences; subclasses can override to customize.
   * The state of {@code bi} should be at the beginning.
   */
  protected Passage[] getSummaryPassagesNoHighlight(int maxPassages) {
    assert breakIterator.current() == breakIterator.first();

    int finalMaxPassages = maxNoHighlightPassages == -1 ? maxPassages : maxNoHighlightPassages;
    // BreakIterator should be un-next'd:
    List<Passage> passages = new ArrayList<>(Math.min(finalMaxPassages, 10));
    int pos = breakIterator.current();
    assert pos == 0;
    while (passages.size() < finalMaxPassages) {
      int next = breakIterator.next();
      if (next == BreakIterator.DONE) {
        break;
      }
      Passage passage = new Passage();
      passage.score = Float.NaN;
      passage.startOffset = pos;
      passage.endOffset = next;
      passages.add(passage);
      pos = next;
    }

    return passages.toArray(new Passage[passages.size()]);
  }

  // algorithm: treat sentence snippets as miniature documents
  // we can intersect these with the postings lists via BreakIterator.preceding(offset),s
  // score each sentence as norm(sentenceStartOffset) * sum(weight * tf(freq))
  protected Passage[] highlightOffsetsEnums(List<OffsetsEnum> offsetsEnums)
      throws IOException {
    PassageScorer scorer = passageScorer;
    BreakIterator breakIterator = this.breakIterator;
    final int contentLength = breakIterator.getText().getEndIndex();

    PriorityQueue<OffsetsEnum> offsetsEnumQueue = new PriorityQueue<>(offsetsEnums.size() + 1);
    for (OffsetsEnum off : offsetsEnums) {
      off.weight = scorer.weight(contentLength, off.postingsEnum.freq());
      off.nextPosition(); // go to first position
      offsetsEnumQueue.add(off);
    }
    offsetsEnumQueue.add(new OffsetsEnum(null, EMPTY)); // a sentinel for termination

    PriorityQueue<Passage> passageQueue = new PriorityQueue<>(Math.min(64, maxPassages + 1), (left, right) -> {
      if (left.score < right.score) {
        return -1;
      } else if (left.score > right.score) {
        return 1;
      } else {
        return left.startOffset - right.startOffset;
      }
    });
    Passage passage = new Passage(); // the current passage in-progress.  Will either get reset or added to queue.

    OffsetsEnum off;
    while ((off = offsetsEnumQueue.poll()) != null) {
      int start = off.startOffset();
      if (start == -1) {
        throw new IllegalArgumentException("field '" + field + "' was indexed without offsets, cannot highlight");
      }
      int end = off.endOffset();
      // LUCENE-5166: this hit would span the content limit... however more valid
      // hits may exist (they are sorted by start). so we pretend like we never
      // saw this term, it won't cause a passage to be added to passageQueue or anything.
      assert EMPTY.startOffset() == Integer.MAX_VALUE;
      if (start < contentLength && end > contentLength) {
        continue;
      }
      // See if this term should be part of a new passage.
      if (start >= passage.endOffset) {
        if (passage.startOffset >= 0) { // true if this passage has terms; otherwise couldn't find any (yet)
          // finalize passage
          passage.score *= scorer.norm(passage.startOffset);
          // new sentence: first add 'passage' to queue
          if (passageQueue.size() == maxPassages && passage.score < passageQueue.peek().score) {
            passage.reset(); // can't compete, just reset it
          } else {
            passageQueue.offer(passage);
            if (passageQueue.size() > maxPassages) {
              passage = passageQueue.poll();
              passage.reset();
            } else {
              passage = new Passage();
            }
          }
        }
        // if we exceed limit, we are done
        if (start >= contentLength) {
          break;
        }
        // advance breakIterator
        passage.startOffset = Math.max(breakIterator.preceding(start + 1), 0);
        passage.endOffset = Math.min(breakIterator.following(start), contentLength);
      }
      // Add this term to the passage.
      int tf = 0;
      while (true) {
        tf++;
        BytesRef term = off.getTerm();// a reference; safe to refer to
        assert term != null;
        passage.addMatch(start, end, term);
        // see if there are multiple occurrences of this term in this passage. If so, add them.
        if (!off.hasMorePositions()) {
          break; // No more in the entire text. Already removed from pq; move on
        }
        off.nextPosition();
        start = off.startOffset();
        end = off.endOffset();
        if (start >= passage.endOffset || end > contentLength) { // it's beyond this passage
          offsetsEnumQueue.offer(off);
          break;
        }
      }
      passage.score += off.weight * scorer.tf(tf, passage.endOffset - passage.startOffset);
    }

    Passage[] passages = passageQueue.toArray(new Passage[passageQueue.size()]);
    for (Passage p : passages) {
      p.sort();
    }
    // sort in ascending order
    Arrays.sort(passages, (left, right) -> left.startOffset - right.startOffset);
    return passages;
  }

  protected static final PostingsEnum EMPTY = new PostingsEnum() {

    @Override
    public int nextPosition() throws IOException {
      return 0;
    }

    @Override
    public int startOffset() throws IOException {
      return Integer.MAX_VALUE;
    }

    @Override
    public int endOffset() throws IOException {
      return Integer.MAX_VALUE;
    }

    @Override
    public BytesRef getPayload() throws IOException {
      return null;
    }

    @Override
    public int freq() throws IOException {
      return 0;
    }

    @Override
    public int docID() {
      return NO_MORE_DOCS;
    }

    @Override
    public int nextDoc() throws IOException {
      return NO_MORE_DOCS;
    }

    @Override
    public int advance(int target) throws IOException {
      return NO_MORE_DOCS;
    }

    @Override
    public long cost() {
      return 0;
    }
  };
}
