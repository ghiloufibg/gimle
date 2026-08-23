import { createContext, useContext } from "react";
import type { GimleData } from "./types";

export const DataContext = createContext<GimleData | null>(null);

export function useGimleData(): GimleData {
  const data = useContext(DataContext);
  if (!data) throw new Error("useGimleData() called outside <DataContext.Provider>");
  return data;
}
