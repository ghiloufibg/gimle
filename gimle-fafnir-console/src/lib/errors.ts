import { ApiError } from "@/repositories/http/apiClient";

export function messageOf(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return "unexpected error";
}

export function isForbidden(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403;
}
