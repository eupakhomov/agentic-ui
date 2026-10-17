import type { Extension } from '@codemirror/state';
import { StreamLanguage } from '@codemirror/language';
import { extensionOf } from './selection';

/**
 * Syntax highlighting by file extension. Every language is a dynamic import, so none of them is in
 * the main bundle (the whole panel is lazy as well). Kotlin has no Lezer grammar; it uses the
 * legacy clike mode — highlighting only, no language service. Unknown extensions get plain text.
 */
export async function loadLanguage(path: string): Promise<Extension | null> {
  const name = path.slice(path.lastIndexOf('/') + 1).toLowerCase();
  const ext = extensionOf(path);
  switch (ext) {
    case 'java':
      return (await import('@codemirror/lang-java')).java();
    case 'kt':
    case 'kts':
      return StreamLanguage.define((await import('@codemirror/legacy-modes/mode/clike')).kotlin);
    case 'ts':
    case 'mts':
    case 'cts':
      return (await import('@codemirror/lang-javascript')).javascript({ typescript: true });
    case 'tsx':
      return (await import('@codemirror/lang-javascript')).javascript({ typescript: true, jsx: true });
    case 'js':
    case 'mjs':
    case 'cjs':
      return (await import('@codemirror/lang-javascript')).javascript();
    case 'jsx':
      return (await import('@codemirror/lang-javascript')).javascript({ jsx: true });
    case 'json':
      return (await import('@codemirror/lang-json')).json();
    case 'md':
    case 'markdown':
      return (await import('@codemirror/lang-markdown')).markdown();
    case 'yaml':
    case 'yml':
      return (await import('@codemirror/lang-yaml')).yaml();
    case 'xml':
    case 'svg':
      return (await import('@codemirror/lang-xml')).xml();
    case 'html':
    case 'htm':
      return (await import('@codemirror/lang-html')).html();
    case 'css':
      return (await import('@codemirror/lang-css')).css();
    case 'sql':
      return (await import('@codemirror/lang-sql')).sql();
    case 'py':
      return (await import('@codemirror/lang-python')).python();
    case 'sh':
    case 'bash':
      return StreamLanguage.define((await import('@codemirror/legacy-modes/mode/shell')).shell);
    case 'gradle':
    case 'groovy':
      return StreamLanguage.define((await import('@codemirror/legacy-modes/mode/groovy')).groovy);
    case 'properties':
      return StreamLanguage.define((await import('@codemirror/legacy-modes/mode/properties')).properties);
    case 'toml':
      return StreamLanguage.define((await import('@codemirror/legacy-modes/mode/toml')).toml);
    default:
      if (name === 'dockerfile') {
        return StreamLanguage.define((await import('@codemirror/legacy-modes/mode/dockerfile')).dockerFile);
      }
      return null;
  }
}
