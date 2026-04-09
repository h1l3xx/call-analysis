export type DepartmentPolicyCallDirection =
  | 'internal_incoming'
  | 'internal_outgoing'
  | 'external_incoming'
  | 'external_outgoing'
  | 'unknown'

export interface DepartmentCallPolicyResponse {
  id: string
  departmentId: string | null
  callDirection: DepartmentPolicyCallDirection
  scriptId: string
  promptTemplateId: string
  createdAt: number
  updatedAt: number
}

export interface UpsertDepartmentCallPolicyRequest {
  departmentId?: string | null
  callDirection: DepartmentPolicyCallDirection
  scriptId: string
  promptTemplateId: string
}

