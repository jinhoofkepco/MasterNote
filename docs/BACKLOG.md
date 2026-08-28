# MasterNote follow-up backlog

## Library

- [ ] Add a per-book delete action beside the existing rename action.
  - Show a confirmation dialog with the exact student and book title.
  - Use `LibraryRepository.hideBook(bookId)` so the book disappears from the shelf and
    content-hash sync candidates without immediately deleting its PDF, attempts, grades, or ink.
  - Do not treat the `아직 시작 전` label alone as proof that the book has no teacher marks or
    annotations.
  - Add regression tests for duplicate PDFs, exact-ID deletion, cancellation, restart persistence,
    and click isolation from the book-open action.
