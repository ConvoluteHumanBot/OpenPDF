/*
  Copyright 2014 by Tizra Inc.
  The contents of this file are subject to the Mozilla Public License Version 1.1
  (the "License"); you may not use this file except in compliance with the License.
  You may obtain a copy of the License at http://www.mozilla.org/MPL/

  Software distributed under the License is distributed on an "AS IS" basis,
  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
  for the specific language governing rights and limitations under the License.

  The Original Code is 'iText, a free JAVA-PDF library'.

  The Initial Developer of the Original Code is Bruno Lowagie. Portions created by
  the Initial Developer are Copyright (C) 1999-2008 by Bruno Lowagie.
  All Rights Reserved.
  Co-Developer of the code is Paulo Soares. Portions created by the Co-Developer
  are Copyright (C) 2000-2008 by Paulo Soares. All Rights Reserved.

  Contributor(s): all the names of the contributors are added in the source code
  where applicable.

  Alternatively, the contents of this file may be used under the terms of the
  LGPL license (the "GNU LIBRARY GENERAL PUBLIC LICENSE"), in which case the
  provisions of LGPL are applicable instead of those above.  If you wish to
  allow use of your version of this file only under the terms of the LGPL
  License and not to allow others to use your version of this file under
  the MPL, indicate your decision by deleting the provisions above and
  replace them with the notice and other provisions required by the LGPL.
  If you do not delete the provisions above, a recipient may use your version
  of this file under either the MPL or the GNU LIBRARY GENERAL PUBLIC LICENSE.

  This library is free software; you can redistribute it and/or modify it
  under the terms of the MPL as stated above or under the terms of the GNU
  Library General Public License as published by the Free Software Foundation;
  either version 2 of the License, or any later version.

  This library is distributed in the hope that it will be useful, but WITHOUT
  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
  FOR A PARTICULAR PURPOSE. See the GNU Library general Public License for more
  details.
 */
package org.openpdf.text.pdf.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author dgd
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class PdfContentTextLocator extends PdfContentStreamHandler {

    private final ArrayList<MatchedPattern> accumulator = new ArrayList<>();

    private final ArrayList<ParsedText> fragments = new ArrayList<>();

    private final int page;
    private Pattern p;
    private float[] coordinates;
    private final MatchingStrategy mode;

    private enum MatchingStrategy {
        PATTERN,
        BBOX,
    }

    /**
     * Construct a content PdfContetStreamHandler for regex-based text extraction pattern
     *
     * @param renderListener the text assembler
     * @param pattern        the pattern to match text against
     * @param page           PdfPage to inspect
     */
    public PdfContentTextLocator(TextAssembler renderListener, String pattern, int page) {
        super(renderListener);
        if (pattern == null) {
            throw new IllegalArgumentException("Pattern cannot be null");
        }
        //We check for length because we want to include whitespaces as possible patterns
        if (pattern.isEmpty()) {
            throw new IllegalArgumentException("Pattern sequence must be longer than 0");
        }
        this.p = Pattern.compile(pattern);
        this.page = page;
        this.mode = MatchingStrategy.PATTERN;
        installDefaultOperators();
        reset();
    }

    /**
     * Construct a content PdfContetStreamHandler for coordinates-based text extraction pattern
     *
     * @param renderListener the text assembler
     * @param coordinates    the bounding box to search text within
     * @param page           PdfPage to inspect
     */
    public PdfContentTextLocator(TextAssembler renderListener, float[] coordinates, int page) {
        super(renderListener);
        if (coordinates.length != 4) {
            throw new IllegalArgumentException("Coordinates bounding box must be an array of "
                    + "four floats, "
                    + "[x1, y1, x2, y2] {lower left point, upper right point}");
        }
        if (coordinates[2] < coordinates[0]) {
            throw new IllegalArgumentException("x2 {coordinates[2]} must be greater than or equal to x1 "
                    + "{coordinates[0]}");
        }
        if (coordinates[3] < coordinates[1]) {
            throw new IllegalArgumentException("y2 {coordinates[3]} must be greater than or equal to y1 "
                    + "{coordinates[1]}");
        }
        this.coordinates = coordinates;
        //We check for length because we want to include whitespaces as possible patterns
        this.page = page;
        this.mode = MatchingStrategy.BBOX;
        installDefaultOperators();
        reset();
    }

    /**
     * Loads all the supported graphics and text state operators in a map.
     */
    @Override
    protected void installDefaultOperators() {
        super.installDefaultOperators();
        registerContentOperator(this.new Do());
    }

    void pushContext(String newContextName) {
        contextNames.push(newContextName);
    }

    /**
     * Assemble partial words on the same line and execute the location strategy.
     *
     */
    void popContext() {
        contextNames.pop();
    }

    /**
     * Search for a pattern in a PdfString and if found, collect its bounding box
     *
     * @param decoded     the text to inspect
     * @param widths      cumulative list of end-widths of each character
     * @param fontFloor   lowest y-coordinate of the font
     * @param fontCeiling highest y-coordinate of the font
     */
    private void matchPdfString(String decoded, List<Float> widths, float fontFloor,
            float fontCeiling) {
        if (widths.size() <= 1) {
            return;
        }
        Matcher m = p.matcher(decoded);
        while (m.find()) {
            int beginning = m.start();
            int end = m.end();
            float x1 = widths.get(beginning);
            float x2 = widths.get(end);
            MatchedPattern mp = new MatchedPattern(decoded, this.page, widths.getFirst(), fontFloor,
                    widths.getLast(), fontCeiling, beginning, end, x1, x2);
            accumulator.add(mp);
        }
    }

    /**
     * Extract text if it's coordinates intersect with the given bounding box
     *
     * @param decoded     the text to inspect
     * @param widths      cumulative list of end-widths of each character
     * @param fontFloor   lowest y-coordinate of the font
     * @param fontCeiling highest y-coordinate of the font
     */
    private void locatePdfString(String decoded, List<Float> widths, float fontFloor,
            float fontCeiling) {
        if (widths.size() <= 1) {
            return;
        }
        float startWidth = widths.getFirst();
        float endWidth = widths.getLast();
        if (startWidth < this.coordinates[0] && endWidth < this.coordinates[0]) {
            return;
        }
        if (startWidth > this.coordinates[2]) {
            return;
        }
        if (fontFloor < this.coordinates[1] && fontCeiling < this.coordinates[1]) {
            return;
        }
        if (fontFloor > this.coordinates[3]) {
            return;
        }

        int nearestLeft = Collections.binarySearch(widths, this.coordinates[0]);
        if (nearestLeft < 0) {
            nearestLeft = -(nearestLeft + 1);
            if (nearestLeft > 0) {
                nearestLeft--;
            }
        }

        int nearestRight = Collections.binarySearch(widths, this.coordinates[2]);
        if (nearestRight < 0) {
            nearestRight = -(nearestRight + 1);
            if (nearestRight != 0) {
                nearestRight--;
            }
        }

        MatchedPattern mp = new MatchedPattern(decoded, this.page, widths.getFirst(),
                fontFloor,
                widths.getLast(),
                fontCeiling,
                nearestLeft, nearestRight, widths.get(nearestLeft), widths.get(nearestRight));
        accumulator.add(mp);
    }

    @Override
    public String getResultantText() {
        return "";
    }

    /**
     * @return list of text strips that matches
     */
    public List<MatchedPattern> getMatchedPatterns() {
        StringBuilder builder = new StringBuilder();
        List<Float> widths = new ArrayList<>();
        float currentY = -1000;
        float totalWidth = 0;
        float fontFloor = 0;
        float fontCeiling = 0;
        ParsedText inProgress = null;
        for (TextAssemblyBuffer tbuff : textFragments) {
            if (!(tbuff instanceof ParsedText)) {
                continue;
            }
            ParsedText pText = (ParsedText) tbuff;
            final float pY = pText.getStartPoint().get(1);
            if (pY != currentY) {
                //assemble current text
                String inspected = builder.toString();
                if (widths.size() > 1) {
                    switch (this.mode) {
                        case MatchingStrategy.PATTERN: {
                            matchPdfString(inspected, widths, fontFloor, fontCeiling);
                            break;
                        }
                        case MatchingStrategy.BBOX: {
                            locatePdfString(inspected, widths, fontFloor, fontCeiling);
                            break;
                        }
                        default: {
                            //do nothing for now
                        }
                    }
                }

                //reset state
                widths.clear();
                totalWidth = pText.getStartPoint().get(0);
                widths.add(totalWidth);
                fontFloor = pY;
                fontCeiling = pY;
                builder = new StringBuilder();
                inProgress = null;
            }

            if (inProgress != null) {
                float dist = inProgress.getEndPoint().subtract(pText.getStartPoint()).get(0);
                // If distance from two textfragments is greater than a single space (thus not part of the same word)
                // we insert a single space char with the width of corresponding distance. Because the two fragments
                // could be of two different font sizes, it would be impractical to find the exact number of spaces.
                // This is of course an edge case that hardly happens in real examples, and usually a matched pattern
                // would not consider the differences between one or more spaces.
                float smallestSpace = Float.min(pText.getSingleSpaceWidth(), inProgress.getSingleSpaceWidth());
                //smallestSpace = smallestSpace / 2.3f;
                if (dist >= smallestSpace) {
                    totalWidth += dist;
                    widths.add(totalWidth);
                    builder.append(" ");
                }
            }

            GraphicsState currentGraphicState = pText.getGraphicState();

            // Walk the character codes rather than the decoded text: a PDF stores glyph widths against codes, and
            // measuring a decoded character instead would have to guess which code produced it. The decoded text and
            // the offsets are built together so that offsets.get(i) stays the x position at which decoded character i
            // starts, which is the invariant the matching below relies on.
            for (char code : pText.getCodePoints()) {
                String decodedCode = currentGraphicState.getFont().decode(code);
                float advance = ParsedText.advanceForCode(code, currentGraphicState);
                if (decodedCode == null || decodedCode.isEmpty()) {
                    // The code carries no text, but the pen still moves past its glyph.
                    totalWidth += advance;
                    continue;
                }
                // One code can decode to several characters, as a ligature does. There is no position to be had
                // inside a single glyph, so spread its advance evenly over the characters it produced.
                float advancePerChar = advance / decodedCode.length();
                for (int k = 0; k < decodedCode.length(); k++) {
                    builder.append(decodedCode.charAt(k));
                    totalWidth += advancePerChar;
                    widths.add(totalWidth);
                }
            }

            float currentFontFloor = pY + currentGraphicState.getFontDescentDescriptor();
            float currentFontCeiling = pY + currentGraphicState.getFontAscentDescriptor();
            if (currentFontFloor < fontFloor) {
                fontFloor = currentFontFloor;
            }
            if (currentFontCeiling > fontCeiling) {
                fontCeiling = currentFontCeiling;
            }

            inProgress = pText;
            currentY = pY;
        }

        if (widths.size() > 1) {
            //assemble current text

            String inspected = builder.toString();
            switch (this.mode) {
                case MatchingStrategy.PATTERN: {
                    matchPdfString(inspected, widths, fontFloor, fontCeiling);
                    break;
                }
                case MatchingStrategy.BBOX: {
                    locatePdfString(inspected, widths, fontFloor, fontCeiling);
                    break;
                }
                default: {
                    //do nothing for now
                }
            }
        }
        return this.accumulator;
    }
}
