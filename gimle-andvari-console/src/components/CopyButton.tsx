import { useState } from "react";
import { Check, Copy } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { copiedMessage, copyActionLabel } from "@/lib/copy";
import { cn } from "@/lib/utils";

export function CopyButton({
  value,
  subject,
  className,
}: {
  value: string;
  /** What is being copied, as a noun phrase -- e.g. "sha256", "repository URL". */
  subject: string;
  className?: string;
}) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      toast.success(copiedMessage(subject));
      setTimeout(() => setCopied(false), 1400);
    } catch {
      toast.error("Clipboard unavailable");
    }
  };

  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      aria-label={copied ? copiedMessage(subject) : copyActionLabel(subject)}
      className={cn("h-7 w-7 rounded-sm", className)}
      onClick={copy}
    >
      {copied ? <Check className="h-3.5 w-3.5 text-status-ok" /> : <Copy className="h-3.5 w-3.5" />}
    </Button>
  );
}
