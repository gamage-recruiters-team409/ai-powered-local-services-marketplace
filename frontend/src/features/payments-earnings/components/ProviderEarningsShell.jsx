import { useCallback } from "react";
import { useNavigate } from "react-router-dom";

import { ProviderLayout } from "../../../shared/layouts/provider";
import { endSession, LOGIN_PATH } from "../../disputes-admin/services/authBridge";

import ProviderEarningsPage from "../pages/ProviderEarningsPage";

/**
 * Provides the shared provider layout and established logout
 * behaviour for the WP07 earnings and payout page.
 */
export default function ProviderEarningsShell() {
  const navigate = useNavigate();

  const logout = useCallback(() => {
    endSession();
    navigate(LOGIN_PATH, { replace: true });
  }, [navigate]);

  return (
    <ProviderLayout onLogout={logout}>
      <ProviderEarningsPage />
    </ProviderLayout>
  );
}
