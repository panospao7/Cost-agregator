import { Sidebar } from "./components/Sidebar";
import { Dashboard } from "./components/Dashboard";

export function App() {
  return (
    <div className="min-h-screen bg-base font-sans">
      <Sidebar />
      <Dashboard />
    </div>
  );
}
