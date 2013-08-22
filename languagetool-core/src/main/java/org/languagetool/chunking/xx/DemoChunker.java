/* LanguageTool, a natural language style checker
 * Copyright (C) 2013 Daniel Naber (http://www.danielnaber.de)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.chunking.xx;

import org.languagetool.AnalyzedToken;
import org.languagetool.AnalyzedTokenReadings;
import org.languagetool.chunking.ChunkTag;
import org.languagetool.chunking.Chunker;

import java.util.List;

/**
 * Demo chunker that assigns chunk {@code B-NP-singular} to the word {@code chunkbar}.
 * @since 2.3
 */
public class DemoChunker implements Chunker {

  @Override
  public void addChunkTags(List<AnalyzedTokenReadings> tokenReadings) {
    for (AnalyzedTokenReadings tokenReading : tokenReadings) {
      if ("chunkbar".equals(tokenReading.getToken())) {
        List<AnalyzedToken> readings = tokenReading.getReadings();
        for (AnalyzedToken reading : readings) {
          reading.setChunkTag(new ChunkTag("B-NP-singular"));
        }
      }
    }
  }

}