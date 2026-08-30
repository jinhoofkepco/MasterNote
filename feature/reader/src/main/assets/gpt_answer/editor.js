(function () {
  "use strict";

  var activePointer = null;
  var selecting = true;
  var lastBlockIndex = -1;
  var captureButton = null;
  var suppressPenClickButton = null;

  function blockFor(element) {
    return element && element.closest ? element.closest(".edit-block") : null;
  }

  function setSelected(block, selected) {
    if (!block) return;
    block.classList.toggle("is-selected", selected);
    var button = block.querySelector(".block-check");
    if (button) button.setAttribute("aria-pressed", selected ? "true" : "false");
  }

  function blocks() {
    return Array.prototype.slice.call(document.querySelectorAll(".edit-block"));
  }

  function blockAtPointer(event) {
    var element = document.elementFromPoint(event.clientX, event.clientY);
    var block = blockFor(element);
    if (!block) return null;
    var button = block.querySelector(".block-check");
    if (!button) return null;
    var bounds = button.getBoundingClientRect();
    return event.clientX >= bounds.left && event.clientX <= bounds.right ? block : null;
  }

  function paintThrough(block) {
    var values = blocks();
    var current = values.indexOf(block);
    if (current < 0) return;
    if (lastBlockIndex < 0) lastBlockIndex = current;
    var first = Math.min(lastBlockIndex, current);
    var last = Math.max(lastBlockIndex, current);
    for (var i = first; i <= last; i += 1) setSelected(values[i], selecting);
    lastBlockIndex = current;
  }

  function endPointer(pointerId) {
    if (pointerId !== undefined && pointerId !== activePointer) return;
    if (captureButton && activePointer !== null && captureButton.hasPointerCapture &&
        captureButton.hasPointerCapture(activePointer)) {
      try { captureButton.releasePointerCapture(activePointer); } catch (_) {}
    }
    activePointer = null;
    lastBlockIndex = -1;
    captureButton = null;
  }

  document.addEventListener("pointerdown", function (event) {
    var button = event.target.closest && event.target.closest(".block-check");
    if (!button) return;
    if (event.isPrimary === false || (event.button !== undefined && event.button !== 0)) return;
    // A finger or mouse uses the normal button click, so a swipe that begins on the
    // narrow rail can still scroll. Only the pen paints a continuous range.
    if (event.pointerType !== "pen") return;
    var block = blockFor(button);
    if (!block) return;
    event.preventDefault();
    activePointer = event.pointerId;
    captureButton = button;
    selecting = !block.classList.contains("is-selected");
    lastBlockIndex = blocks().indexOf(block);
    setSelected(block, selecting);
    if (button.setPointerCapture) {
      try { button.setPointerCapture(activePointer); } catch (_) {}
    }
  }, true);

  document.addEventListener("pointermove", function (event) {
    if (event.pointerId !== activePointer) return;
    var block = blockAtPointer(event);
    if (block) paintThrough(block);
    event.preventDefault();
  }, true);

  function finishPointer(event) {
    if (event.pointerId !== activePointer) return;
    if (event.type === "pointerup") {
      suppressPenClickButton = captureButton;
      window.setTimeout(function () { suppressPenClickButton = null; }, 0);
    }
    endPointer(event.pointerId);
    event.preventDefault();
  }

  document.addEventListener("pointerup", finishPointer, true);
  document.addEventListener("pointercancel", finishPointer, true);
  document.addEventListener("lostpointercapture", function (event) {
    endPointer(event.pointerId);
  }, true);
  document.addEventListener("click", function (event) {
    var button = event.target.closest && event.target.closest(".block-check");
    if (!button) return;
    if (button === suppressPenClickButton) {
      suppressPenClickButton = null;
      event.preventDefault();
      return;
    }
    var block = blockFor(button);
    setSelected(block, !block.classList.contains("is-selected"));
  }, true);
  window.addEventListener("blur", function () { suppressPenClickButton = null; endPointer(); });
  window.addEventListener("pagehide", function () { suppressPenClickButton = null; endPointer(); });

  window.MasterNoteAnswerEditor = Object.freeze({
    generation: function () {
      return new URL(window.location.href).searchParams.get("generation") || "";
    },
    selectedOrdinals: function () {
      var selected = document.querySelectorAll(".edit-block.is-selected");
      var values = [];
      for (var i = 0; i < selected.length; i += 1) {
        var value = Number(selected[i].getAttribute("data-block-ordinal"));
        if (Number.isSafeInteger(value) && value >= 0) values.push(value);
      }
      values.sort(function (left, right) { return left - right; });
      return values;
    }
  });
})();
