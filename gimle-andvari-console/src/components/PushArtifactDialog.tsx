import { useRef, useState } from "react";
import { UploadCloud } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { formatBytes } from "@/lib/format";
import { readModuleCoordinate } from "@/lib/moduleDescriptor";
import { cn } from "@/lib/utils";
import { useArtifactsStore } from "@/stores/artifactsStore";

export function PushArtifactDialog({ defaultModuleId = "" }: { defaultModuleId?: string }) {
  const [open, setOpen] = useState(false);
  const [moduleId, setModuleId] = useState(defaultModuleId);
  const [version, setVersion] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  // Set once the picked jar's own bundled gimle-module.yaml has been read: while it holds a
  // coordinate, moduleId/version are locked to that coordinate rather than left open to a typed
  // mismatch -- see readModuleCoordinate's own doc for why. Stays null for a vessel jar (no
  // bundled descriptor), which leaves the fields exactly as editable as they've always been.
  const [derivedFrom, setDerivedFrom] = useState<string | null>(null);
  const [derivingCoordinate, setDerivingCoordinate] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const push = useArtifactsStore((s) => s.push);
  const pending = useArtifactsStore((s) => s.pushPending);
  const error = useArtifactsStore((s) => s.pushError);
  const conflict = useArtifactsStore((s) => s.pushConflict);
  const resetPushState = useArtifactsStore((s) => s.resetPushState);

  const onOpenChange = (next: boolean) => {
    setOpen(next);
    if (!next) {
      resetPushState();
      setVersion("");
      setFile(null);
      setDerivedFrom(null);
      setDerivingCoordinate(false);
      setModuleId(defaultModuleId);
    }
  };

  const pickFile = (picked: File) => {
    setFile(picked);
    setDerivedFrom(null);
    setDerivingCoordinate(true);
    void readModuleCoordinate(picked).then((coordinate) => {
      setDerivingCoordinate(false);
      if (!coordinate) return;
      setModuleId(coordinate.moduleId);
      setVersion(coordinate.version);
      setDerivedFrom(picked.name);
    });
  };

  const submit = async () => {
    if (!moduleId || !version || !file) {
      toast.error("moduleId, version and a jar file are required");
      return;
    }
    const result = await push(moduleId, version, file);
    if (result) {
      toast.success(`Pushed ${result.moduleId}:${result.version}`, {
        description: `sha256 ${result.sha256}`,
      });
      onOpenChange(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogTrigger asChild>
        <Button size="sm" className="rounded-sm">
          <UploadCloud className="mr-1.5 h-4 w-4" />
          Push artifact
        </Button>
      </DialogTrigger>
      <DialogContent className="rounded-sm sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Push artifact</DialogTitle>
          <DialogDescription>
            Uploads are immutable — a stored version can never be overwritten. The registry stores
            whatever bytes it is given under the coordinate you name here: it never opens the jar,
            so a module jar pushed under a coordinate its own{" "}
            <span className="font-mono">gimle-module.yaml</span> does not declare is accepted here
            and fails later, at deploy. Push a module jar with{" "}
            <span className="font-mono">gimle artifact push</span>, which reads the coordinate off
            the jar instead of taking one; name a coordinate by hand only for a jar that carries no
            descriptor at all.
          </DialogDescription>
        </DialogHeader>

        {error ? (
          <p
            className={cn(
              "rounded-sm border px-3 py-2 text-xs",
              conflict
                ? "border-status-warn/30 bg-status-warn-bg text-status-warn"
                : "border-status-bad/30 bg-status-bad-bg text-status-bad",
            )}
          >
            {error}
          </p>
        ) : null}

        <div className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="moduleId" className="hud-label">
              module id
            </Label>
            <Input
              id="moduleId"
              value={moduleId}
              placeholder="com.example.greeter-provider"
              onChange={(e) => setModuleId(e.target.value)}
              readOnly={!!derivedFrom}
              disabled={!!derivedFrom}
              className="rounded-sm font-mono text-xs disabled:opacity-100"
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="version" className="hud-label">
              version
            </Label>
            <Input
              id="version"
              value={version}
              placeholder="1.0.0"
              onChange={(e) => setVersion(e.target.value)}
              readOnly={!!derivedFrom}
              disabled={!!derivedFrom}
              className="rounded-sm font-mono text-xs disabled:opacity-100"
            />
          </div>
          {derivedFrom ? (
            <p className="text-xs text-muted-foreground">
              derived from {derivedFrom}&apos;s own gimle-module.yaml -- the coordinate a jar is
              stored under always matches what it declares for itself
            </p>
          ) : derivingCoordinate ? (
            <p className="text-xs text-muted-foreground">reading gimle-module.yaml…</p>
          ) : file ? (
            <p className="text-xs text-muted-foreground">
              no gimle-module.yaml found in this jar -- enter the coordinate to push it under
            </p>
          ) : null}

          <div className="space-y-1.5">
            <p className="hud-label">jar file</p>
            <button
              type="button"
              onClick={() => inputRef.current?.click()}
              onDragOver={(e) => {
                e.preventDefault();
                setDragging(true);
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={(e) => {
                e.preventDefault();
                setDragging(false);
                const dropped = e.dataTransfer.files[0];
                if (dropped) pickFile(dropped);
              }}
              className={cn(
                "flex w-full flex-col items-center gap-1.5 rounded-sm border border-dashed px-4 py-6 text-center transition-colors",
                dragging ? "border-primary bg-accent/40" : "border-border hover:border-primary/60",
              )}
            >
              <UploadCloud className="h-5 w-5 text-muted-foreground" />
              <span className="font-mono text-xs">
                {file
                  ? `${file.name} · ${formatBytes(file.size)}`
                  : "drop a .jar or click to browse"}
              </span>
            </button>
            <input
              ref={inputRef}
              type="file"
              accept=".jar,application/java-archive"
              className="hidden"
              onChange={(e) => {
                const picked = e.target.files?.[0];
                if (picked) pickFile(picked);
              }}
            />
          </div>
        </div>

        <DialogFooter>
          <Button
            onClick={() => void submit()}
            disabled={pending}
            className="w-full rounded-sm sm:w-auto"
          >
            {pending ? "Uploading…" : "Upload"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
