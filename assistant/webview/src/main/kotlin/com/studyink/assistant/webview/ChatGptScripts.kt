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

    val responseSnapshot: String =
        """
        (function() {
          function visible(el) {
            if (!el || !el.getBoundingClientRect) return false;
            const r = el.getBoundingClientRect(); const s = getComputedStyle(el);
            return r.width > 0 && r.height > 0 && s.visibility !== "hidden" && s.display !== "none";
          }
          function norm(value) { return String(value || "").replace(/\s+/g, " ").trim(); }
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
          const text = norm(last && (last.innerText || last.textContent));
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
            hash: hash(text),
            actionsReady: actionsReady,
            stopVisible: /stop|중지|정지|생성 중지|응답 중지/i.test(labels),
            uploadingVisible: /uploading|첨부 중|업로드|파일을 처리/i.test(labels)
          });
        })();
        """.trimIndent()

    val latestResponseHtml: String =
        """
        (function() {
          function norm(value) { return String(value || "").replace(/\s+/g, " ").trim(); }
          const direct = Array.from(document.querySelectorAll("[data-message-author-role='assistant']"))
            .filter(function(el) { return norm(el.innerText || el.textContent).length > 0; });
          const articles = direct.length ? [] : Array.from(document.querySelectorAll("main article"))
            .filter(function(el) { return norm(el.innerText || el.textContent).length > 0; });
          const assistants = direct.length ? direct : articles;
          const last = assistants.length ? assistants[assistants.length - 1] : null;
          if (!last) return "";
          const selector = ".markdown, .prose, [class*='markdown'], [class*='prose']";
          const content = (last.matches && last.matches(selector)) ? last :
            (last.querySelector(selector) || last);
          const clone = content.cloneNode(true);
          clone.querySelectorAll("script,style,button,svg,canvas,form,input,textarea,select,iframe,object,embed,img,picture,source,audio,video,link,meta").forEach(function(el) {
            el.remove();
          });
          clone.querySelectorAll("*").forEach(function(el) {
            const safeHref = el.tagName === "A" && /^https:\/\//i.test(
              String(el.getAttribute("href") || "")
            ) ? String(el.getAttribute("href")) : "";
            Array.from(el.attributes || []).forEach(function(attr) {
              el.removeAttribute(attr.name);
            });
            if (safeHref) {
              el.setAttribute("href", safeHref);
              el.setAttribute("target", "_blank");
              el.setAttribute("rel", "noopener noreferrer");
            }
          });
          return clone.innerHTML || "";
        })();
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
