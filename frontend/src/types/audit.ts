// 감사 로그 타입 (AUDIT-3). BE DTO 와 1:1 정합 — Long→number, LocalDateTime→string(ISO), nullable Java→`| null`.

export type AuditAction =
  | 'LOGIN_SUCCESS'
  | 'LOGIN_FAILURE'
  | 'ACL_BLOCKED'
  | 'RATE_LIMIT_BLOCKED'
  | 'EVIDENCE_APPROVE'
  | 'EVIDENCE_REJECT'
  | 'SCRIPT_CREATE'
  | 'SCRIPT_UPDATE'
  | 'SCRIPT_ROLLBACK'
  | 'SCRIPT_DELETE'
  | 'USER_CREATE'
  | 'USER_UPDATE'
  | 'USER_DELETE'
  | 'FRAMEWORK_CHANGE'
  | 'TREE_CHANGE'
  | 'FILE_UPLOAD'
  | 'FILE_DELETE';

export type AuditResult = 'SUCCESS' | 'FAILURE' | 'BLOCKED';

export interface AuditLog {
  id: number;
  actorUserId: number | null;
  actorEmail: string | null;
  action: AuditAction;
  targetType: string | null;
  targetId: string | null;
  detail: string | null;
  clientIp: string | null;
  result: AuditResult;
  createdAt: string; // ISO-8601
}

export interface AuditLogPage {
  content: AuditLog[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

export interface AuditLogSearchParams {
  actorUserId?: number;
  action?: AuditAction;
  result?: AuditResult;
  from?: string; // ISO LocalDateTime (예: 2026-06-09T12:34)
  to?: string;
  page?: number;
  size?: number;
}