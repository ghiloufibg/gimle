import { AlertTriangle } from "lucide-react";

export function ErrorBanner({ message }: { message: string }) {
  return (
    <div className="flex items-start gap-2 border border-status-bad/50 bg-status-bad-bg px-3 py-2 text-sm text-status-bad">
      <AlertTriangle className="mt-0.5 size-4 shrink-0" />
      <span className="font-mono text-xs leading-relaxed break-all">{message}</span>
    </div>
  );
}
