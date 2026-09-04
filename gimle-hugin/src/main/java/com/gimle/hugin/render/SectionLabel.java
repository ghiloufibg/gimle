package com.gimle.hugin.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The line naming a section, and whatever qualifies it: what the section is, how much of it there
 * is, what is wrong with it, and what filter is currently narrowing it.
 *
 * <p>The counterpart to {@link TitleBar}, and there for the same reason. Eight screens were each
 * assembling this line themselves, which meant eight copies of the filter clause alone -- and a
 * filter that appears in a different place on each screen is one an operator has to look for rather
 * than glance at.
 *
 * <p>The filter is therefore drawn last wherever it is set, whatever order the caller mentioned it
 * in. Everything else appears in the order the screen listed it, because only the screen knows
 * which of its own qualifiers matters most.
 */
final class SectionLabel {

  private record Clause(String text, Style style) {}

  private final Painter painter;
  private final String name;
  private final List<Clause> clauses = new ArrayList<>();
  private Optional<String> filter = Optional.empty();

  private SectionLabel(final Painter painter, final String name) {
    this.painter = painter;
    this.name = name;
  }

  /** A section named in upper case, however the caller spelled it. */
  static SectionLabel of(final Painter painter, final String name) {
    return new SectionLabel(painter, name.toUpperCase(Locale.ROOT));
  }

  /** What the section is, said immediately after its name and set close to it. */
  SectionLabel detail(final String text) {
    return clause(text, 2, Style.fg(Palette.MUTED_FOREGROUND));
  }

  /** The same position, for a value worth the eye -- the identity a screen is answering for. */
  SectionLabel subject(final String text) {
    return clause(text, 2, Style.fg(Palette.PRIMARY));
  }

  /** A further qualifier, set apart from the ones before it. */
  SectionLabel note(final String text) {
    return clause(text, 3, Style.fg(Palette.MUTED_FOREGROUND));
  }

  /** A qualifier about the reading itself rather than about the thing read, dimmer for it. */
  SectionLabel aside(final String text) {
    return clause(text, 3, Style.fg(Palette.MUTED));
  }

  /** A named value worth the eye, in the same position a note takes. */
  SectionLabel value(final String label, final String text) {
    clause(label, 3, Style.fg(Palette.MUTED_FOREGROUND));
    return clause(text, 1, Style.fg(Palette.PRIMARY));
  }

  /** Something wrong with what the section is showing, in the colour of how wrong. */
  SectionLabel alert(final String text, final StatusVariant variant) {
    return clause(text, 3, Style.fg(variant));
  }

  /** The filter narrowing this section, drawn last wherever it was mentioned. */
  SectionLabel filter(final String value) {
    filter = Optional.ofNullable(value).filter(text -> !text.isBlank());
    return this;
  }

  String build() {
    return line().build();
  }

  /**
   * The label as a line still open for building on, for the one screen that puts something of its
   * own at the far right of it. Handing back the rendered string instead would lose the visible
   * width the padding depends on, since the string carries escape sequences that occupy no column.
   */
  Line line() {
    Line line = new Line(painter).add(name, Style.fg(Palette.HUD).asBold());
    for (Clause clause : clauses) {
      line.add(clause.text(), clause.style());
    }
    filter.ifPresent(
        value ->
            line.add("   filter ", Style.fg(Palette.MUTED_FOREGROUND))
                .add(value, Style.fg(Palette.PRIMARY)));
    return line;
  }

  private SectionLabel clause(final String text, final int gap, final Style style) {
    clauses.add(new Clause(" ".repeat(gap) + text, style));
    return this;
  }
}
