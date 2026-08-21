import { Navigate, Route, Routes } from "react-router-dom";
import { useAuth, useRole } from "./auth/AuthContext";
import { LoginScreen } from "./auth/LoginScreen";
import { Layout } from "./components/Layout";
import { ApprovalQueueScreen } from "./screens/ApprovalQueueScreen";
import { CaseDetailScreen } from "./screens/CaseDetailScreen";
import { ExceptionQueueScreen } from "./screens/ExceptionQueueScreen";
import { InstructionLookupScreen } from "./screens/InstructionLookupScreen";

/**
 * Brief §5 asks whether a checker should land on the approval queue instead
 * of the shared exception queue, since approval is their entire job. A pure
 * checker (no MAKER role) is redirected there; a dual-role user (MAKER and
 * CHECKER both, e.g. local user `dual1`) lands on the queue like a maker
 * does, since they work both queues. See PHASE-9-REPORT.md §5 for the full
 * reasoning.
 */
function Landing() {
  const isChecker = useRole("CHECKER");
  const isMaker = useRole("MAKER");
  if (isChecker && !isMaker) {
    return <Navigate to="/approvals" replace />;
  }
  return <ExceptionQueueScreen />;
}

export function App() {
  const { session } = useAuth();

  if (!session) {
    return <LoginScreen />;
  }

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<Landing />} />
        <Route path="/cases/:caseId" element={<CaseDetailScreen />} />
        <Route path="/approvals" element={<ApprovalQueueScreen />} />
        <Route path="/lookup" element={<InstructionLookupScreen />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
