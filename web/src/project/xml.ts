// A small, strict XML reader for 3MF model and config parts. Untrusted input: DOCTYPE, entities other than the five
// predefined ones, and processing instructions other than the XML declaration are refused, so there is no entity
// expansion or external resource loading of any kind.

export interface XmlElement {
  name: string; // local name, prefix stripped
  qname: string; // as written, e.g. "p:path" style names on attributes are kept in attrs
  attrs: Record<string, string>;
  children: XmlElement[];
  text: string;
}

export class XmlError extends Error {}

const ENTITIES: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };

function decode(s: string): string {
  return s.replace(/&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);/g, (_, e: string) => {
    if (e[0] === '#') {
      const code = e[1] === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
      if (!Number.isFinite(code) || code > 0x10ffff) throw new XmlError('Invalid character reference.');
      return String.fromCodePoint(code);
    }
    const v = ENTITIES[e];
    if (v === undefined) throw new XmlError('Unsupported XML entity.');
    return v;
  });
}

export function parseXml(text: string, maxElements = 50_000_000): XmlElement {
  if (/<!DOCTYPE/i.test(text) || /<!ENTITY/i.test(text)) throw new XmlError('XML with a document type is not accepted.');
  const root: XmlElement = { name: '#root', qname: '#root', attrs: {}, children: [], text: '' };
  const stack: XmlElement[] = [root];
  let i = 0;
  let count = 0;
  const n = text.length;
  while (i < n) {
    const lt = text.indexOf('<', i);
    if (lt < 0) break;
    if (lt > i) stack[stack.length - 1].text += decode(text.slice(i, lt));
    if (text.startsWith('<?', lt)) {
      const end = text.indexOf('?>', lt);
      if (end < 0) throw new XmlError('Unterminated declaration.');
      if (!/^<\?xml[\s?]/.test(text.slice(lt, lt + 6))) throw new XmlError('Processing instructions are not accepted.');
      i = end + 2;
      continue;
    }
    if (text.startsWith('<!--', lt)) {
      const end = text.indexOf('-->', lt);
      if (end < 0) throw new XmlError('Unterminated comment.');
      i = end + 3;
      continue;
    }
    if (text.startsWith('<![CDATA[', lt)) {
      const end = text.indexOf(']]>', lt);
      if (end < 0) throw new XmlError('Unterminated CDATA.');
      stack[stack.length - 1].text += text.slice(lt + 9, end);
      i = end + 3;
      continue;
    }
    const gt = text.indexOf('>', lt);
    if (gt < 0) throw new XmlError('Unterminated tag.');
    let tag = text.slice(lt + 1, gt);
    if (tag.startsWith('/')) {
      const name = tag.slice(1).trim();
      const open = stack.pop();
      if (!open || open.qname !== name) throw new XmlError(`Mismatched closing tag </${name}>.`);
      i = gt + 1;
      continue;
    }
    const selfClosing = tag.endsWith('/');
    if (selfClosing) tag = tag.slice(0, -1);
    const m = /^([^\s/>]+)/.exec(tag);
    if (!m) throw new XmlError('Malformed tag.');
    const qname = m[1];
    const attrs: Record<string, string> = {};
    const attrRe = /([^\s=]+)\s*=\s*("([^"]*)"|'([^']*)')/g;
    let a: RegExpExecArray | null;
    const rest = tag.slice(qname.length);
    while ((a = attrRe.exec(rest))) attrs[a[1]] = decode(a[3] ?? a[4] ?? '');
    const el: XmlElement = { name: qname.includes(':') ? qname.split(':')[1] : qname, qname, attrs, children: [], text: '' };
    if (++count > maxElements) throw new XmlError('Too many XML elements.');
    stack[stack.length - 1].children.push(el);
    if (!selfClosing) stack.push(el);
    i = gt + 1;
  }
  if (stack.length !== 1) throw new XmlError('The XML ended early.');
  const top = root.children[0];
  if (!top) throw new XmlError('Empty XML document.');
  return top;
}

export const child = (e: XmlElement, name: string) => e.children.find((c) => c.name === name);
export const children = (e: XmlElement, name: string) => e.children.filter((c) => c.name === name);

export function escapeXml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
