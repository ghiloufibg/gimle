package com.gimle.hugin.term;

/**
 * One decoded keystroke. Arrow keys arrive as multi-byte escape sequences and printable characters
 * as themselves, so the loop is written against this rather than against raw bytes.
 */
public sealed interface Key {

  /** A printable character the operator typed. */
  record Character(char value) implements Key {}

  /** A key with no character of its own. */
  record Named(Kind kind) implements Key {}

  /** The named keys the view reacts to. */
  enum Kind {
    UP,
    DOWN,
    ENTER,
    ESCAPE,
    BACKSPACE,
    /** Ctrl-C: quit, exactly as {@code q} does, restoring the terminal on the way out. */
    INTERRUPT,
    /** The stream ended -- the terminal went away underneath us. */
    END_OF_INPUT
  }

  static Key of(final char value) {
    return new Character(value);
  }

  static Key named(final Kind kind) {
    return new Named(kind);
  }

  default boolean is(final Kind kind) {
    return this instanceof Named named && named.kind() == kind;
  }

  default boolean isChar(final char value) {
    return this instanceof Character character && character.value() == value;
  }
}
