import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { getAccessToken } from '../utils/authStorage';

export default function ProtectedRoute() {
  const location = useLocation();

  if (!getAccessToken()) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  return <Outlet />;
}
