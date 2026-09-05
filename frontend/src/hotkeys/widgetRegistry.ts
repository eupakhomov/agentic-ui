import type { PermissionResponse } from '../components/PermissionCard';

/**
 * Imperative per-widget actions that the global hotkey listener (Dashboard-level) needs
 * to reach into a specific SessionWidget for: there's no shared React tree between them
 * (Dashboard renders one SessionWidget per grid item, each owns its own WS/composer/git
 * panel state), so a plain module-level registry stands in for a ref callback prop.
 */
export interface WidgetActions {
  focusComposer: () => void;
  toggleGit: () => void;
  respondPermission: (requestId: string, response: PermissionResponse) => void;
}

const registry = new Map<string, WidgetActions>();

export function registerWidget(sessionId: string, actions: WidgetActions): void {
  registry.set(sessionId, actions);
}

export function unregisterWidget(sessionId: string): void {
  registry.delete(sessionId);
}

export function getWidget(sessionId: string): WidgetActions | undefined {
  return registry.get(sessionId);
}
