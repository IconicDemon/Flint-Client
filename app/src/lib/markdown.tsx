import DOMPurify from "dompurify";
import { marked } from "marked";
import { useMemo, type MouseEvent } from "react";
import { api } from "./api";

const ALLOWED_TAGS = [
  "a", "b", "blockquote", "br", "code", "del", "details", "div", "em", "h1", "h2", "h3", "h4", "h5", "h6",
  "hr", "i", "img", "kbd", "li", "ol", "p", "pre", "s", "span", "strong", "sub", "summary", "sup",
  "table", "tbody", "td", "th", "thead", "tr", "u", "ul", "center", "picture", "source",
];
const ALLOWED_ATTR = ["href", "src", "srcset", "alt", "title", "width", "height", "align", "colspan", "rowspan", "open"];

let hooked = false;
function ensureHooks() {
  if (hooked) return;
  hooked = true;
  DOMPurify.addHook("afterSanitizeAttributes", (node) => {
    const el = node as Element;
    if (el.tagName === "A") {
      const href = el.getAttribute("href") ?? "";
      if (!/^https:\/\//i.test(href)) el.removeAttribute("href");
      el.setAttribute("rel", "noopener noreferrer");
    }
    if (el.tagName === "IMG" || el.tagName === "SOURCE") {
      for (const attr of ["src", "srcset"]) {
        const value = el.getAttribute(attr);
        if (value && !value.split(",").every((part) => /^\s*https:\/\//i.test(part))) el.removeAttribute(attr);
      }
      el.setAttribute("loading", "lazy");
      el.setAttribute("referrerpolicy", "no-referrer");
    }
  });
}

/** Converts Modrinth markdown (which may contain raw HTML) into sanitized markup. */
export function sanitizeMarkdown(source: string): string {
  ensureHooks();
  const html = marked.parse(source, { async: false, gfm: true, breaks: false }) as string;
  return DOMPurify.sanitize(html, { ALLOWED_TAGS, ALLOWED_ATTR, ALLOW_DATA_ATTR: false, FORBID_TAGS: ["style", "script", "iframe", "form", "input", "object", "embed"] });
}

export function Markdown({ source, className = "" }: { source: string; className?: string }) {
  const html = useMemo(() => sanitizeMarkdown(source), [source]);
  const onClick = (e: MouseEvent<HTMLDivElement>) => {
    const link = (e.target as HTMLElement).closest("a");
    if (!link) return;
    e.preventDefault();
    const href = link.getAttribute("href");
    if (href) void api.openExternal(href);
  };
  return <div className={`markdown ${className}`} onClick={onClick} dangerouslySetInnerHTML={{ __html: html }} />;
}
