import client from './client'
import type { DepartmentCallPolicyResponse, UpsertDepartmentCallPolicyRequest } from '@/types'

export const departmentCallPoliciesApi = {
  list() {
    return client.get<DepartmentCallPolicyResponse[]>('/api/v1/department-call-policies')
  },
  upsert(payload: UpsertDepartmentCallPolicyRequest) {
    return client.post<DepartmentCallPolicyResponse>('/api/v1/department-call-policies', payload)
  },
  removeDepartmentOverride(departmentId: string, direction: string) {
    return client.delete(`/api/v1/department-call-policies/departments/${departmentId}/${direction}`)
  },
  removePairPolicies(departmentIdA: string, departmentIdB: string) {
    return client.delete(`/api/v1/department-call-policies/pair/${departmentIdA}/${departmentIdB}`)
  },
}

