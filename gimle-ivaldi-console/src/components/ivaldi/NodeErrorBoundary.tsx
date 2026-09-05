import { Component, type ReactNode } from "react";

interface Props {
  /** Changing this remounts the boundary, so selecting another node recovers. */
  resetKey: string;
  children: ReactNode;
}

interface State {
  message: string | null;
}

/**
 * Keeps one malformed node from taking the whole designer down: the form for
 * that node degrades to an "incomplete node" state and the canvas stays usable.
 */
export class NodeErrorBoundary extends Component<Props, State> {
  override state: State = { message: null };

  static getDerivedStateFromError(error: Error): State {
    return { message: error.message };
  }

  override componentDidUpdate(previous: Props) {
    if (previous.resetKey !== this.props.resetKey && this.state.message)
      this.setState({ message: null });
  }

  override render() {
    if (this.state.message)
      return (
        <div className="rounded-sm border border-status-warn/50 bg-status-warn-bg p-2">
          <div className="hud-label text-status-warn">Incomplete node</div>
          <p className="mt-1 text-[11px] text-muted-foreground">
            This node is missing data the form needs, so its fields cannot be shown. Delete it and
            add it again, or fix the stored file.
          </p>
          <p className="num mt-1 text-[10px] text-muted-foreground">{this.state.message}</p>
        </div>
      );
    return this.props.children;
  }
}
