/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.pp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import de.uka.ilkd.key.control.TermLabelVisibilityManager;
import de.uka.ilkd.key.java.Services;
import de.uka.ilkd.key.logic.JTerm;
import de.uka.ilkd.key.pp.IdentitySequentPrintFilter;
import de.uka.ilkd.key.pp.InitialPositionTable;
import de.uka.ilkd.key.pp.LogicPrinter;
import de.uka.ilkd.key.pp.NotationInfo;
import de.uka.ilkd.key.pp.Range;
import de.uka.ilkd.key.pp.SequentViewLogicPrinter;

import org.key_project.logic.PosInTerm;
import org.key_project.logic.Term;
import org.key_project.prover.sequent.PosInOccurrence;
import org.key_project.prover.sequent.Semisequent;
import org.key_project.prover.sequent.Sequent;
import org.key_project.prover.sequent.SequentFormula;
import org.key_project.server.dto.SequentFormat;
import org.key_project.server.dto.SequentSide;
import org.key_project.server.dto.SequentView;
import org.key_project.server.dto.StructuredFormula;
import org.key_project.util.collection.ImmutableList;

import org.jspecify.annotations.Nullable;

/**
 * Renders a sequent in each of the shapes the protocol offers.
 *
 * <p>
 * The structured shape is the one worth explaining. A proof obligation in mid-flight prints as a
 * single formula several hundred characters long: a symbolic state, then the Java still to run,
 * then what must hold once it has. Reading that means parsing KeY's syntax before you can find
 * out what the goal is, and for a reader working from the text alone that is most of the work.
 *
 * <p>
 * KeY already knows where those pieces are. Its own editor highlights updates and Java blocks, and
 * the machinery that does so — a position table built while printing — is in {@code key.core} and
 * has nothing to do with Swing. This asks that machinery the same questions and reports the
 * answers, so the split is KeY's rather than a parser of our own that would drift from it.
 */
public final class SequentRenderer {

    private SequentRenderer() {
    }

    /**
     * Renders a sequent.
     *
     * @param sequent the sequent to render
     * @param services the services of the proof it belongs to
     * @param format how to render it
     * @return the rendered sequent
     */
    public static SequentView render(Sequent sequent, Services services, SequentFormat format) {
        if (format == SequentFormat.STRUCTURED) {
            return structured(sequent, services);
        }
        boolean unicode = format == SequentFormat.UNICODE;
        List<String> antecedent = new ArrayList<>();
        for (SequentFormula formula : sequent.antecedent()) {
            antecedent.add(print(formula, services, unicode));
        }
        List<String> succedent = new ArrayList<>();
        for (SequentFormula formula : sequent.succedent()) {
            succedent.add(print(formula, services, unicode));
        }
        return new SequentView(antecedent, succedent, format, null);
    }

    private static String print(SequentFormula formula, Services services, boolean unicode) {
        Term term = formula.formula();
        if (!(term instanceof JTerm jTerm)) {
            return String.valueOf(term);
        }
        return LogicPrinter.quickPrintTerm(jTerm, services, NotationInfo.DEFAULT_PRETTY_SYNTAX,
            unicode, NotationInfo.DEFAULT_HIDE_PACKAGE_PREFIX).trim();
    }

    /**
     * Prints the whole sequent once and takes each formula apart using the position table.
     *
     * <p>
     * The sequent is printed as a whole rather than formula by formula because that is what the
     * position table indexes; slicing it back apart afterwards keeps every offset meaningful.
     *
     * @param sequent the sequent to render
     * @param services the services of the proof it belongs to
     * @return the rendered sequent, with its formulas separated
     */
    private static SequentView structured(Sequent sequent, Services services) {
        NotationInfo notation = new NotationInfo();
        notation.refresh(services, NotationInfo.DEFAULT_PRETTY_SYNTAX, false,
            NotationInfo.DEFAULT_HIDE_PACKAGE_PREFIX);
        SequentViewLogicPrinter printer = SequentViewLogicPrinter.positionPrinter(notation,
            services, new TermLabelVisibilityManager());
        IdentitySequentPrintFilter filter = new IdentitySequentPrintFilter();
        filter.setSequent(sequent);
        printer.printFilteredSequent(filter);

        String text = printer.result();
        InitialPositionTable table = printer.layouter().getInitialPositionTable();

        List<StructuredFormula> formulas = new ArrayList<>();
        List<String> antecedent = new ArrayList<>();
        List<String> succedent = new ArrayList<>();
        collect(sequent.antecedent(), true, SequentSide.ANTECEDENT, filter, table, text, formulas,
            antecedent);
        collect(sequent.succedent(), false, SequentSide.SUCCEDENT, filter, table, text, formulas,
            succedent);
        return new SequentView(antecedent, succedent, SequentFormat.STRUCTURED,
            List.copyOf(formulas));
    }

    private static void collect(Semisequent side, boolean antecedent, SequentSide label,
            IdentitySequentPrintFilter filter, InitialPositionTable table, String text,
            List<StructuredFormula> formulas, List<String> plain) {
        int index = 0;
        for (SequentFormula formula : side) {
            PosInOccurrence position =
                new PosInOccurrence(formula, PosInTerm.getTopLevel(), antecedent);
            ImmutableList<Integer> path = table.pathForPosition(position, filter);
            Range range = path == null ? null : table.rangeForPath(path);
            if (range == null || range.start() < 0 || range.end() > text.length()
                    || range.start() >= range.end()) {
                // The printer could not place this formula. Reporting it as an unsplit whole is
                // honest; inventing a split would not be.
                String whole = String.valueOf(formula.formula());
                plain.add(whole);
                formulas.add(new StructuredFormula(label, index, whole, null, null, whole));
                index++;
                continue;
            }
            String whole = tidy(text.substring(range.start(), range.end()));
            plain.add(whole);
            formulas.add(new StructuredFormula(label, index, whole,
                sliceWithin(table.getUpdateRanges(), range, text),
                sliceWithin(table.getJavaBlockRanges(), range, text),
                elide(range, table, text)));
            index++;
        }
    }

    /**
     * Finds the first range of a kind that lies inside a formula.
     *
     * @param candidates every range of that kind in the whole sequent
     * @param within the formula's own range
     * @param text the printed sequent
     * @return the text of the first one inside, or {@code null} when there is none
     */
    private static @Nullable String sliceWithin(Range[] candidates, Range within, String text) {
        for (Range candidate : candidates) {
            if (candidate.start() >= within.start() && candidate.end() <= within.end()) {
                return tidy(text.substring(candidate.start(), candidate.end()));
            }
        }
        return null;
    }

    /**
     * Reprints a formula with its state and its program cut out.
     *
     * <p>
     * What is left is the part that says what has to be true, which in a formula of a few hundred
     * characters is usually the last thirty. The cuts are marked rather than dropped, so it stays
     * clear that something stood there.
     *
     * @param within the formula's range
     * @param table the position table of the printed sequent
     * @param text the printed sequent
     * @return the formula with its state and program replaced by an ellipsis
     */
    private static String elide(Range within, InitialPositionTable table, String text) {
        List<Range> cuts = new ArrayList<>();
        gather(table.getUpdateRanges(), within, cuts);
        gather(table.getJavaBlockRanges(), within, cuts);
        if (cuts.isEmpty()) {
            return tidy(text.substring(within.start(), within.end()));
        }
        cuts.sort(Comparator.comparingInt(Range::start));

        StringBuilder out = new StringBuilder();
        int cursor = within.start();
        for (Range cut : cuts) {
            if (cut.start() < cursor) {
                // Nested inside one already cut out; it is gone with its parent.
                continue;
            }
            out.append(text, cursor, cut.start()).append('…');
            cursor = cut.end();
        }
        out.append(text, cursor, within.end());
        return tidy(out.toString());
    }

    private static void gather(Range[] candidates, Range within, List<Range> into) {
        for (Range candidate : candidates) {
            if (candidate.start() >= within.start() && candidate.end() <= within.end()) {
                into.add(candidate);
            }
        }
    }

    /**
     * Collapses the layouter's indentation.
     *
     * <p>
     * The printer wraps and indents for a terminal of a certain width. Those line breaks carry no
     * meaning once a formula has been separated from the ones around it, and they make the text
     * harder to read rather than easier.
     *
     * @param text the sliced text
     * @return the same text on one line
     */
    private static String tidy(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
