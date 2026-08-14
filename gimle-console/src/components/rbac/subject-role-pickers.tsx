import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

export type SubjectType = "user" | "group";

/** Subject-type toggle + name input; composes into "user:<name>" / "group:<name>". */
export function SubjectPicker({
  type,
  name,
  onTypeChange,
  onNameChange,
}: {
  type: SubjectType;
  name: string;
  onTypeChange: (t: SubjectType) => void;
  onNameChange: (n: string) => void;
}) {
  return (
    <div className="grid gap-1">
      <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">Subject</Label>
      <div className="flex items-center gap-2">
        <Select value={type} onValueChange={(v) => onTypeChange(v as SubjectType)}>
          <SelectTrigger className="h-8 w-28 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="user" className="text-xs">
              User
            </SelectItem>
            <SelectItem value="group" className="text-xs">
              Group
            </SelectItem>
          </SelectContent>
        </Select>
        <Input
          className="h-8 w-48 font-mono text-xs"
          placeholder="name"
          value={name}
          onChange={(e) => onNameChange(e.target.value)}
        />
      </div>
    </div>
  );
}

/** Role dropdown, falling back to free text while the roles list is unavailable. */
export function RolePicker({
  value,
  roleNames,
  onChange,
}: {
  value: string;
  roleNames: string[];
  onChange: (v: string) => void;
}) {
  return (
    <div className="grid gap-1">
      <Label className="text-[10px] uppercase tracking-wider text-muted-foreground">Role</Label>
      {roleNames.length > 0 ? (
        <Select value={value} onValueChange={onChange}>
          <SelectTrigger className="h-8 w-52 font-mono text-xs">
            <SelectValue placeholder="Pick role" />
          </SelectTrigger>
          <SelectContent>
            {roleNames.map((r) => (
              <SelectItem key={r} value={r} className="font-mono text-xs">
                {r}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      ) : (
        <Input
          className="h-8 w-52 font-mono text-xs"
          placeholder="role name"
          value={value}
          onChange={(e) => onChange(e.target.value)}
        />
      )}
    </div>
  );
}
