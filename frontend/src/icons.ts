/**
 * The app's whole icon vocabulary, in one place.
 *
 * Every icon is a 16px, 1.5px-stroke, `currentColor` line glyph (defaults set once by
 * <LucideProvider> in main.tsx) — the IntelliJ-style monochrome language. Icons never
 * carry their own colour: they inherit it from the button/chip they sit in, so theming,
 * hover and state colouring all come free. Colour encodes *state* only (see styles.css
 * "icon + colour policy"), never decoration.
 *
 * Importing through this module rather than from 'lucide-react' directly keeps the
 * vocabulary reviewable and makes a rename/swap a one-file change.
 */
export {
  // topbar
  Bell as NotifyOn,
  BellOff as NotifyOff,
  RefreshCw as Refresh,
  LayoutTemplate as Templates,
  Cpu as SystemSession,
  // a rising line, not bars: at 16px vertical bars read too close to Library's book spines
  ChartLine as Usage,
  Library as SkillLibrary,
  Brain as Memory,
  Compass as ServiceDiscovery,
  Keyboard as Shortcuts,
  LayoutGrid as Expose,
  Settings as SettingsIcon,
  Plus as New,
  Zap as QuickSession,

  // session widget header
  FolderTree as EcosystemContext,
  Ticket as LinkedTicket,
  History as ContinuedFrom,
  GitFork as ChildOf,
  GitPullRequest as PullRequest,
  Maximize2 as Maximize,
  Minimize2 as Restore,
  Minus as MinimizeToDock,
  GitBranch as GitPanelIcon,
  Copy as Duplicate,
  Download as DownloadIcon,
  Square as Interrupt,
  Play as Resume,
  X as Close,

  // dialogs & panels
  Sparkles as AiSuggest,
  Folder as LocalSource,
  Cloud as RemoteSource,
  BookOpen as SkillAsset,
  Bot as AgentAsset,
  FilePen as MemoryDocEditable,
  FileText as MemoryDoc,
  BrushCleaning as Cleanup,
  TriangleAlert as Warning,
  ArrowDown as PullChanges,

  // transcript
  Check as ToolOk,
  X as ToolFailed,
  LoaderCircle as ToolRunning,
} from 'lucide-react';
