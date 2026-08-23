import { useEffect, useState } from "react";
import { Routes, Route } from "react-router-dom";
import { fetchGimleData } from "./api";
import { DataContext } from "./DataContext";
import type { GimleData } from "./types";
import Layout from "./components/Layout";
import Dashboard from "./pages/Dashboard";
import RequirementList from "./pages/RequirementList";
import RequirementDetail from "./pages/RequirementDetail";

type LoadState =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; data: GimleData };

export default function App() {
  const [state, setState] = useState<LoadState>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;
    fetchGimleData()
      .then((data) => {
        if (!cancelled) setState({ status: "ready", data });
      })
      .catch((err: Error) => {
        if (!cancelled) setState({ status: "error", message: err.message });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (state.status === "loading") {
    return (
      <div className="boot-screen">
        <div className="boot-mark" aria-hidden="true" />
        <p>Loading requirements data…</p>
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="boot-screen boot-error">
        <div className="boot-mark" aria-hidden="true" />
        <h1>Couldn't read the requirements files</h1>
        <p className="mono">{state.message}</p>
        <p className="boot-hint">
          Point <span className="mono">GIMLE_DATA_DIR</span> (dev) or <span className="mono">--data-dir</span>{" "}
          (production server) at a directory containing <span className="mono">requirements-matrix.json</span>,{" "}
          <span className="mono">rtm.json</span> and <span className="mono">uat-checklist.json</span>, then reload.
        </p>
      </div>
    );
  }

  return (
    <DataContext.Provider value={state.data}>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/" element={<Dashboard />} />
          <Route path="/requirements" element={<RequirementList />} />
          <Route path="/requirements/:id" element={<RequirementDetail />} />
        </Route>
      </Routes>
    </DataContext.Provider>
  );
}
