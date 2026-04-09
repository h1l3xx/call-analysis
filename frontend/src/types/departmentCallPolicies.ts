export interface DepartmentCallPolicyResponse {
  id: string
  departmentId: string | null
  callDirection: 'internal' | 'external_incoming' | 'external_outgoing' | 'unknown'
  scriptId: string
  promptTemplateId: string
  createdAt: number
  updatedAt: number
}

export interface UpsertDepartmentCallPolicyRequest {
  departmentId?: string | null
  callDirection: 'internal' | 'external_incoming' | 'external_outgoing' | 'unknown'
  scriptId: string
  promptTemplateId: string
}

