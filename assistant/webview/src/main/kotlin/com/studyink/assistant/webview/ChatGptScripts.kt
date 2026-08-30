package com.studyink.assistant.webview

import org.json.JSONObject

internal object ChatGptScripts {
    const val INPUT =
        "#prompt-textarea, [data-testid='prompt-textarea'], " +
            "div[contenteditable='true'], .ProseMirror"
    const val SEND =
        "button[data-testid='send-button'], button[data-testid='composer-submit-button'], " +
            "button[data-testid='composer-send-button'], button[aria-label='Send prompt'], " +
            "button[aria-label='Send message'], button[aria-label='Send']"

    fun selectorStatus(): String =
        """
        (function() {
          function matched(list) {
            const selectors = list.split(',').map(function(value) { return value.trim(); });
            for (const selector of selectors) {
              try { if (document.querySelector(selector)) return selector; } catch (_) {}
            }
            return null;
          }
          return JSON.stringify({
            input: matched(${JSONObject.quote(INPUT)}),
            send: matched(${JSONObject.quote(SEND)})
          });
        })();
        """.trimIndent()

    fun visibleComposer(): String =
        """
        (function() {
          const nodes = Array.from(document.querySelectorAll(${JSONObject.quote(INPUT)}));
          return nodes.some(function(node) {
            const r = node.getBoundingClientRect();
            const s = window.getComputedStyle(node);
            return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
          });
        })();
        """.trimIndent()

    fun inject(prompt: String, imageBase64: String?, token: String): String =
        PINNED_INJECTION_TEMPLATE
            .replace("%INPUT%", JSONObject.quote(INPUT))
            .replace("%SEND%", JSONObject.quote(SEND))
            .replace("%TOKEN%", JSONObject.quote(token))
            .replace("%B64%", imageBase64?.let(JSONObject::quote) ?: "null")
            .replace("%PROMPT%", JSONObject.quote(prompt))

    fun injectionStatus(token: String): String =
        """
        (function(expectedToken) {
          const state = window.__studyInkGptGateway;
          if (!state || state.token !== expectedToken) {
            return JSON.stringify({status:'missing', error:''});
          }
          return JSON.stringify({status:String(state.status || ''), error:String(state.error || '')});
        })(${JSONObject.quote(token)});
        """.trimIndent()

    val responseSnapshot: String
        get() =
        """
        (function() {
          function visible(el) {
            if (!el || !el.getBoundingClientRect) return false;
            const r = el.getBoundingClientRect(); const s = getComputedStyle(el);
            return r.width > 0 && r.height > 0 && s.visibility !== "hidden" && s.display !== "none";
          }
          function norm(value) { return String(value || "").replace(/\s+/g, " ").trim(); }
          function preserved(value) {
            return String(value || "").replace(/\r\n?/g, "\n").trim();
          }
          function label(el) {
            return norm([el && el.innerText, el && el.ariaLabel, el && el.title,
              el && el.getAttribute && el.getAttribute("aria-label"),
              el && el.getAttribute && el.getAttribute("data-testid")].filter(Boolean).join(" "));
          }
          function hash(value) {
            const s = String(value || "");
            return s.length + ":" + s.slice(0, 100) + ":" + s.slice(-180);
          }
          const direct = Array.from(document.querySelectorAll("[data-message-author-role='assistant']"))
            .filter(function(el) { return norm(el.innerText || el.textContent).length > 0; });
          const articles = direct.length ? [] : Array.from(document.querySelectorAll("main article"))
            .filter(function(el) { return norm(el.innerText || el.textContent).length > 0; });
          const assistants = direct.length ? direct : articles;
          const last = assistants.length ? assistants[assistants.length - 1] : null;
          const text = preserved(last && (last.innerText || last.textContent));
          const scope = last && (last.closest("[data-testid^='conversation-turn']") ||
            last.closest("article") || last.parentElement);
          const actionWords = /copy|copied|copy-turn-action-button|good response|bad response|regenerate|share|복사|좋아요|싫어요|공유|다시 생성/i;
          const scopedButtons = scope ? Array.from(scope.querySelectorAll("button,[role='button']")) : [];
          const actionsReady = scopedButtons.filter(visible).some(function(el) {
            return actionWords.test(label(el));
          });
          const buttons = Array.from(document.querySelectorAll("button,[role='button']")).filter(visible);
          const labels = buttons.map(label).join("\n");
          return JSON.stringify({
            assistantCount: assistants.length,
            text: text,
            hash: hash(norm(text)),
            actionsReady: actionsReady,
            stopVisible: /stop|중지|정지|생성 중지|응답 중지/i.test(labels),
            uploadingVisible: /uploading|첨부 중|업로드|파일을 처리/i.test(labels)
          });
        })();
        """.trimIndent()

    val latestResponseMarkdown: String
        get() =
        """
        (function() {
          $RESPONSE_EXTRACTION_HELPERS
          function norm(value) { return String(value || "").replace(/\s+/g, " ").trim(); }
          const direct = Array.from(document.querySelectorAll("[data-message-author-role='assistant']"))
            .filter(function(el) { return norm(el.innerText || el.textContent).length > 0; });
          const articles = direct.length ? [] : Array.from(document.querySelectorAll("main article"))
            .filter(function(el) { return norm(el.innerText || el.textContent).length > 0; });
          const assistants = direct.length ? direct : articles;
          const last = assistants.length ? assistants[assistants.length - 1] : null;
          return extractStudyInkMarkdown(last);
        })();
        """.trimIndent()

    val latestResponseHtml: String
        get() =
        """
        (function() {
          $RESPONSE_EXTRACTION_HELPERS
          function norm(value) { return String(value || "").replace(/\s+/g, " ").trim(); }
          const direct = Array.from(document.querySelectorAll("[data-message-author-role='assistant']"))
            .filter(function(el) { return norm(el.innerText || el.textContent).length > 0; });
          const articles = direct.length ? [] : Array.from(document.querySelectorAll("main article"))
            .filter(function(el) { return norm(el.innerText || el.textContent).length > 0; });
          const assistants = direct.length ? direct : articles;
          const last = assistants.length ? assistants[assistants.length - 1] : null;
          if (!last) return "";
          return extractStudyInkHtml(last);
        })();
        """.trimIndent()

    /**
     * Kept in the evaluated page instead of depending on ChatGPT's CSS or JavaScript. The page's
     * rendered KaTeX/MathML is reduced to one TeX source before hidden accessibility nodes are
     * removed, then the remaining semantic DOM is converted to portable Markdown.
     */
    private val RESPONSE_EXTRACTION_HELPERS =
        """
          const STUDYINK_DOLLAR = String.fromCharCode(36);
          const STUDYINK_CONTENT_SELECTOR =
            ".markdown, .prose, [class*='markdown'], [class*='prose']";
          const STUDYINK_MATH_SELECTOR =
            ".katex-display, .katex, mjx-container, math";

          function cleanStudyInkCharacters(value) {
            return String(value || "")
              .replace(/\r\n?/g, "\n")
              .replace(/[\u00a0\u2028\u2029]/g, function(ch) {
                return ch === "\u00a0" ? " " : "\n";
              })
              .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f\u00ad\u200b-\u200d\u202a-\u202e\u2060\u2066-\u2069\ufeff\ufffd]/g, "");
          }

          // Text nodes are prose, not Markdown source. Escape only inline punctuation that our
          // portable renderer could otherwise reinterpret (for example x_1 or ${'$'}5~${'$'}10).
          function escapeStudyInkMarkdownText(value) {
            return cleanStudyInkCharacters(value).replace(
              /([\\*_\[\]~\x24\x60])/g,
              function(marker) { return "\\" + marker; }
            ).replace(
              /(^|\n)([ \t]{0,3})(#{1,6}|>)(?=\s|${'$'})/g,
              function(_, line, indent, marker) { return line + indent + "\\" + marker; }
            ).replace(
              /(^|\n)([ \t]*)([-+])(?=\s)/g,
              function(_, line, indent, marker) { return line + indent + "\\" + marker; }
            ).replace(
              /(^|\n)([ \t]*)(\d+)([.)])(?=\s)/g,
              function(_, line, indent, number, marker) {
                return line + indent + number + "\\" + marker;
              }
            );
          }

          function studyInkContentRoot(message) {
            if (!message) return null;
            return (message.matches && message.matches(STUDYINK_CONTENT_SELECTOR)) ? message :
              (message.querySelector(STUDYINK_CONTENT_SELECTOR) || message);
          }

          function stripStudyInkMathDelimiters(value) {
            let text = cleanStudyInkCharacters(value).trim();
            if (text.startsWith("\\[") && text.endsWith("\\]")) {
              return text.slice(2, -2).trim();
            }
            if (text.startsWith("\\(") && text.endsWith("\\)")) {
              return text.slice(2, -2).trim();
            }
            const doubled = STUDYINK_DOLLAR + STUDYINK_DOLLAR;
            if (text.startsWith(doubled) && text.endsWith(doubled) && text.length >= 4) {
              return text.slice(2, -2).trim();
            }
            if (text.startsWith(STUDYINK_DOLLAR) &&
                text.endsWith(STUDYINK_DOLLAR) && text.length >= 2) {
              return text.slice(1, -1).trim();
            }
            return text;
          }

          function studyInkMathSource(node) {
            if (!node) return "";
            const annotations = [];
            if (node.matches && node.matches("annotation")) annotations.push(node);
            Array.from(node.querySelectorAll && node.querySelectorAll("annotation") || [])
              .forEach(function(value) { annotations.push(value); });
            const annotation = annotations.find(function(value) {
              return /tex|latex/i.test(String(value.getAttribute("encoding") || ""));
            }) || annotations.find(function(value) {
              const candidate = stripStudyInkMathDelimiters(value.textContent);
              return /\\[A-Za-z]+|[_^{}]/.test(candidate);
            });
            if (annotation) {
              const annotated = stripStudyInkMathDelimiters(annotation.textContent);
              if (annotated) return annotated;
            }
            const sourceNode = (node.matches &&
                node.matches("[data-tex],[data-latex],[data-math],[data-formula],[data-expression]"))
              ? node
              : node.querySelector(
                  "[data-tex],[data-latex],[data-math],[data-formula],[data-expression]"
                );
            if (sourceNode) {
              const names = [
                "data-tex", "data-latex", "data-math", "data-formula", "data-expression"
              ];
              for (const name of names) {
                const source = stripStudyInkMathDelimiters(sourceNode.getAttribute(name));
                if (source) return source;
              }
            }
            const altNode = (node.matches && node.matches("[alttext]"))
              ? node : node.querySelector("[alttext]");
            if (altNode) {
              const source = stripStudyInkMathDelimiters(altNode.getAttribute("alttext"));
              if (source) return source;
            }
            const ariaSource = stripStudyInkMathDelimiters(
              node.getAttribute && node.getAttribute("aria-label")
            );
            if (/\\[A-Za-z]+|[_^{}]/.test(ariaSource)) return ariaSource;
            // A generic MathML aria-label may be spoken prose, so accept it as TeX only when it
            // contains TeX structure. The caller preserves visible glyphs separately otherwise.
            return "";
          }

          function studyInkMathFallback(node) {
            if (!node) return "";
            const visual = node.querySelector && node.querySelector(".katex-html");
            return cleanStudyInkCharacters(
              visual && visual.textContent || node.textContent || ""
            ).replace(/\s+/g, " ").trim();
          }

          function isStudyInkDisplayMath(node) {
            if (!node) return false;
            if (node.classList && node.classList.contains("katex-display")) return true;
            const tag = String(node.tagName || "").toLowerCase();
            const display = String(node.getAttribute && node.getAttribute("display") || "")
              .toLowerCase();
            return display === "block" || display === "true" ||
              (tag === "math" && display === "block");
          }

          function replaceStudyInkMath(root) {
            if (!root || !root.querySelectorAll) return;
            Array.from(root.querySelectorAll(STUDYINK_MATH_SELECTOR)).forEach(function(node) {
              if (!root.contains(node) || node.closest("pre,code")) return;
              let ancestor = node.parentElement;
              while (ancestor && ancestor !== root) {
                if (ancestor.matches && ancestor.matches(STUDYINK_MATH_SELECTOR)) return;
                ancestor = ancestor.parentElement;
              }
              const tex = studyInkMathSource(node);
              const display = isStudyInkDisplayMath(node);
              if (!tex) {
                const fallback = studyInkMathFallback(node);
                if (!fallback) return;
                const replacement = document.createElement(display ? "div" : "span");
                replacement.setAttribute("data-studyink-math-fallback", "true");
                replacement.textContent = fallback;
                node.replaceWith(replacement);
                return;
              }
              const marker = display
                ? "\n\n" + STUDYINK_DOLLAR + STUDYINK_DOLLAR + "\n" + tex +
                  "\n" + STUDYINK_DOLLAR + STUDYINK_DOLLAR + "\n\n"
                : STUDYINK_DOLLAR + tex + STUDYINK_DOLLAR;
              const replacement = document.createElement(display ? "div" : "span");
              replacement.setAttribute("data-studyink-math", display ? "display" : "inline");
              replacement.setAttribute("data-studyink-tex", tex);
              // HTML fallback remains readable after attributes are stripped.
              replacement.textContent = marker;
              node.replaceWith(replacement);
            });
          }

          function removeStudyInkArtifacts(root) {
            if (!root || !root.querySelectorAll) return;
            root.querySelectorAll(
              "script,style,button,svg,canvas,form,input,textarea,select,iframe,object,embed," +
              "img,picture,source,audio,video,link,meta,[hidden],[aria-hidden='true']," +
              ".sr-only,.visually-hidden,[data-testid*='copy-turn'],[data-testid*='feedback']," +
              "[data-testid*='citation']"
            ).forEach(function(el) { el.remove(); });
          }

          function prepareStudyInkClone(message) {
            const source = studyInkContentRoot(message);
            if (!source) return null;
            const clone = source.cloneNode(true);
            replaceStudyInkMath(clone);
            removeStudyInkArtifacts(clone);
            return clone;
          }

          function studyInkChildren(node, context) {
            return Array.from(node.childNodes || []).map(function(child) {
              return studyInkNodeToMarkdown(child, context || {});
            }).join("");
          }

          function studyInkInline(value) {
            return cleanStudyInkCharacters(value)
              .replace(/[\t\f\v ]+/g, " ")
              .replace(/ *\n+ */g, " ")
              .trim();
          }

          function studyInkFence(value) {
            const matches = String(value || "").match(/`+/g) || [];
            let longest = 0;
            matches.forEach(function(item) { longest = Math.max(longest, item.length); });
            return "`".repeat(Math.max(3, longest + 1));
          }

          function studyInkList(list, depth) {
            const ordered = String(list.tagName || "").toLowerCase() === "ol";
            let number = parseInt(list.getAttribute("start") || "1", 10);
            if (!Number.isFinite(number)) number = 1;
            const indent = "  ".repeat(depth || 0);
            const lines = [];
            Array.from(list.children || []).filter(function(child) {
              return String(child.tagName || "").toLowerCase() === "li";
            }).forEach(function(item) {
              const nested = [];
              const body = Array.from(item.childNodes || []).map(function(child) {
                if (child.nodeType === 1 && /^(ul|ol)$/i.test(child.tagName)) {
                  nested.push(child);
                  return "";
                }
                return studyInkNodeToMarkdown(child, {listDepth:(depth || 0) + 1});
              }).join("");
              const prefix = ordered ? String(number++) + ". " : "- ";
              const compact = studyInkInline(body);
              const continuation = compact.split("\n").join("\n" + indent + "  ");
              lines.push(indent + prefix + continuation);
              nested.forEach(function(childList) {
                lines.push(studyInkList(childList, (depth || 0) + 1).trimEnd());
              });
            });
            return "\n\n" + lines.join("\n") + "\n\n";
          }

          function studyInkTable(table) {
            const rows = Array.from(table.querySelectorAll("tr")).map(function(row) {
              return Array.from(row.children || []).filter(function(cell) {
                return /^(th|td)$/i.test(cell.tagName);
              }).map(function(cell) {
                return studyInkInline(studyInkChildren(cell, {}))
                  .replace(/\|/g, "\\|");
              });
            }).filter(function(row) { return row.length > 0; });
            if (!rows.length) return "";
            const width = rows.reduce(function(maximum, row) {
              return Math.max(maximum, row.length);
            }, 0);
            rows.forEach(function(row) {
              while (row.length < width) row.push("");
            });
            const line = function(row) { return "| " + row.join(" | ") + " |"; };
            const output = [line(rows[0]), line(rows[0].map(function() { return "---"; }))];
            rows.slice(1).forEach(function(row) { output.push(line(row)); });
            return "\n\n" + output.join("\n") + "\n\n";
          }

          function studyInkNodeToMarkdown(node, context) {
            if (!node) return "";
            if (node.nodeType === 3) {
              return escapeStudyInkMarkdownText(node.nodeValue).replace(/[\t\f\v ]+/g, " ");
            }
            if (node.nodeType !== 1) return "";
            const tag = String(node.tagName || "").toLowerCase();
            const mathKind = String(node.getAttribute("data-studyink-math") || "");
            if (mathKind) {
              const tex = String(node.getAttribute("data-studyink-tex") || "").trim();
              if (!tex) return "";
              return mathKind === "display"
                ? "\n\n" + STUDYINK_DOLLAR + STUDYINK_DOLLAR + "\n" + tex + "\n" +
                  STUDYINK_DOLLAR + STUDYINK_DOLLAR + "\n\n"
                : STUDYINK_DOLLAR + tex + STUDYINK_DOLLAR;
            }
            if (tag === "br") return "\n";
            if (/^h[1-6]$/.test(tag)) {
              const level = parseInt(tag.slice(1), 10);
              return "\n\n" + "#".repeat(level) + " " +
                studyInkInline(studyInkChildren(node, context)) + "\n\n";
            }
            if (tag === "ul" || tag === "ol") {
              return studyInkList(node, Number(context && context.listDepth || 0));
            }
            if (tag === "table") return studyInkTable(node);
            if (tag === "pre") {
              const raw = cleanStudyInkCharacters(node.textContent).replace(/\n+$/g, "");
              const code = node.querySelector("code");
              const classes = String(code && code.className || "");
              const language = (classes.match(/(?:^|\s)language-([\w+-]+)/) || [])[1] || "";
              const fence = studyInkFence(raw);
              return "\n\n" + fence + language + "\n" + raw + "\n" + fence + "\n\n";
            }
            const body = studyInkChildren(node, context);
            if (tag === "code") {
              const value = studyInkInline(body);
              if (!value) return "";
              const fence = studyInkFence(value);
              return fence + (value.startsWith("`") || value.endsWith("`") ? " " : "") +
                value + (value.startsWith("`") || value.endsWith("`") ? " " : "") + fence;
            }
            if (tag === "strong" || tag === "b") {
              const value = studyInkInline(body);
              return value ? "**" + value + "**" : "";
            }
            if (tag === "em" || tag === "i") {
              const value = studyInkInline(body);
              return value ? "*" + value + "*" : "";
            }
            if (tag === "del" || tag === "s" || tag === "strike") {
              const value = studyInkInline(body);
              return value ? "~~" + value + "~~" : "";
            }
            if (tag === "a") {
              const label = studyInkInline(body);
              const href = String(node.getAttribute("href") || "");
              return label && /^https:\/\//i.test(href) ? "[" + label + "](" + href + ")" : label;
            }
            if (tag === "blockquote") {
              const value = cleanStudyInkCharacters(body).trim();
              return value ? "\n\n" + value.split("\n").map(function(line) {
                return "> " + line;
              }).join("\n") + "\n\n" : "";
            }
            if (tag === "hr") return "\n\n---\n\n";
            if (tag === "p" || tag === "div" || tag === "section" || tag === "article" ||
                tag === "header" || tag === "footer" || tag === "details" || tag === "summary" ||
                tag === "dl" || tag === "dt" || tag === "dd" || tag === "figure" ||
                tag === "figcaption") {
              const value = cleanStudyInkCharacters(body).trim();
              return value ? "\n\n" + value + "\n\n" : "";
            }
            return body;
          }

          function finishStudyInkMarkdown(value) {
            // Do not globally collapse blank lines or trailing spaces: they may belong to a
            // fenced code block. Markdown renderers already collapse surplus block spacing.
            return cleanStudyInkCharacters(value).trim();
          }

          function extractStudyInkMarkdown(message) {
            const clone = prepareStudyInkClone(message);
            return clone ? finishStudyInkMarkdown(studyInkChildren(clone, {})) : "";
          }

          function extractStudyInkHtml(message) {
            const clone = prepareStudyInkClone(message);
            if (!clone) return "";
            clone.querySelectorAll("*").forEach(function(el) {
              const safeHref = String(el.tagName || "").toLowerCase() === "a" &&
                  /^https:\/\//i.test(String(el.getAttribute("href") || ""))
                ? String(el.getAttribute("href")) : "";
              Array.from(el.attributes || []).forEach(function(attr) {
                el.removeAttribute(attr.name);
              });
              if (safeHref) {
                el.setAttribute("href", safeHref);
                el.setAttribute("target", "_blank");
                el.setAttribute("rel", "noopener noreferrer");
              }
            });
            return cleanStudyInkCharacters(clone.innerHTML || "").trim();
          }
        """.trimIndent()

    private val PINNED_INJECTION_TEMPLATE =
        """
        (function(promptText, b64, token) {
          const state = {token:token, status:'working', error:''};
          window.__studyInkGptGateway = state;
          function fail(message) {
            if (window.__studyInkGptGateway === state) {
              state.status = 'error'; state.error = String(message || 'unknown injection error');
            }
          }
          function visible(el) {
            if (!el || !el.getBoundingClientRect) return false;
            const r = el.getBoundingClientRect(); const s = getComputedStyle(el);
            return r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 &&
              r.top < innerHeight && r.left < innerWidth &&
              s.visibility !== 'hidden' && s.display !== 'none';
          }
          function pick(selector) {
            const all = Array.from(document.querySelectorAll(selector));
            const shown = all.filter(visible);
            return (shown.length ? shown : all).slice(-1)[0] || null;
          }
          function editorText(el) {
            return String(el && ('value' in el ? el.value : (el.innerText || el.textContent)) || '');
          }
          function hasPrompt(el) {
            const value = editorText(el).replace(/\s+/g, ' ').trim();
            const expected = String(promptText || '').replace(/\s+/g, ' ').trim();
            const prefix = expected.slice(0, Math.min(36, expected.length));
            const tail = expected.slice(Math.max(0, expected.length - 36));
            return !!expected && value.includes(prefix) && value.includes(tail);
          }
          function putPrompt(el) {
            if (!el) return false;
            el.focus();
            if ('value' in el) {
              let proto = Object.getPrototypeOf(el); let descriptor = null;
              while (proto && !descriptor) {
                descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
                proto = Object.getPrototypeOf(proto);
              }
              if (descriptor && descriptor.set) descriptor.set.call(el, promptText);
              else el.value = promptText;
            } else {
              const range = document.createRange(); const selection = getSelection();
              range.selectNodeContents(el); selection.removeAllRanges(); selection.addRange(range);
              let inserted = false;
              try { inserted = document.execCommand('insertText', false, promptText); } catch (_) {}
              if (!inserted) el.textContent = promptText;
            }
            el.dispatchEvent(new InputEvent('input', {
              bubbles:true, inputType:'insertText', data:promptText
            }));
            el.dispatchEvent(new Event('change', {bubbles:true}));
            return hasPrompt(el);
          }
          function attachmentEvidence() {
            const selectors = [
              "[data-testid*='attachment']",
              "[data-testid*='file-thumbnail']",
              "[data-testid*='upload']",
              "button[aria-label*='Remove file']",
              "button[aria-label*='Remove image']",
              "button[aria-label*='첨부 파일 삭제']",
              "button[aria-label*='이미지 삭제']",
              "img[src^='blob:']",
              "img[alt*='Uploaded']",
              "img[alt*='업로드']"
            ];
            const nodes = new Set();
            selectors.forEach(function(selector) {
              try { document.querySelectorAll(selector).forEach(function(node) { nodes.add(node); }); }
              catch (_) {}
            });
            document.querySelectorAll("input[type='file']").forEach(function(input) {
              try { if (input.files && input.files.length) nodes.add(input); } catch (_) {}
            });
            return nodes.size;
          }
          const composer = pick(%INPUT%);
          if (!composer) { fail('composer not found'); return 'composer-not-found'; }
          composer.focus();
          const attachmentBaseline = b64 ? attachmentEvidence() : 0;
          try {
            if (b64) {
              const bytes = atob(b64); const arr = new Uint8Array(bytes.length);
              for (let i = 0; i < bytes.length; i++) arr[i] = bytes.charCodeAt(i);
              const dt = new DataTransfer();
              dt.items.add(new File([arr], 'screen.png', {type:'image/png'}));
              composer.dispatchEvent(new ClipboardEvent('paste', {
                clipboardData:dt, bubbles:true, cancelable:true
              }));
            }
          } catch (error) {
            fail('image paste failed: ' + String(error && error.message || error));
            return 'image-paste-failed';
          }
          putPrompt(composer);
          let tries = 0;
          const timer = setInterval(function() {
            if (window.__studyInkGptGateway !== state) { clearInterval(timer); return; }
            const activeComposer = pick(%INPUT%);
            if (activeComposer && !hasPrompt(activeComposer)) putPrompt(activeComposer);
            const btn = pick(%SEND%);
            const imageConfirmed = !b64 || attachmentEvidence() > attachmentBaseline;
            if (btn && !btn.disabled && activeComposer && hasPrompt(activeComposer) && imageConfirmed) {
              clearInterval(timer); btn.click(); state.status = 'sent';
            } else if (++tries > 180) {
              clearInterval(timer);
              fail(b64 && !imageConfirmed ? 'image attachment not confirmed' : 'send button not ready');
            }
          }, 500);
          return 'started';
        })(%PROMPT%, %B64%, %TOKEN%);
        """.trimIndent()
}
