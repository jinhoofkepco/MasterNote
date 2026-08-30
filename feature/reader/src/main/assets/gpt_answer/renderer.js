(function () {
  "use strict";

  var nodes = document.querySelectorAll(".math-inline, .math-display");
  for (var i = 0; i < nodes.length; i += 1) {
    var node = nodes[i];
    var raw = node.textContent || "";
    var display = node.classList.contains("math-display");
    var open = display ? "\\[" : "\\(";
    var close = display ? "\\]" : "\\)";
    var source = raw;

    if (raw.slice(0, open.length) === open && raw.slice(-close.length) === close) {
      source = raw.slice(open.length, raw.length - close.length);
    }

    if (!window.katex || source.length > 4000) {
      node.classList.add("math-fallback");
      continue;
    }

    try {
      window.katex.render(source, node, {
        displayMode: display,
        output: "htmlAndMathml",
        throwOnError: true,
        strict: "ignore",
        trust: false,
        maxSize: 20,
        maxExpand: 500
      });
    } catch (_) {
      node.textContent = raw;
      node.classList.add("math-fallback");
    }
  }
})();
