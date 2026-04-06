import client from './client'
import type { PromptTemplateResponse } from '@/types'

export const promptTemplatesApi = {
  list() {
    return client.get<PromptTemplateResponse[]>('/api/v1/prompt-templates')
  },
  get(id: string) {
    return client.get<PromptTemplateResponse>(`/api/v1/prompt-templates/${id}`)
  },
  update(id: string, content: string) {
    return client.put<PromptTemplateResponse>(`/api/v1/prompt-templates/${id}`, { content })
  },
  reset(id: string) {
    return client.post<PromptTemplateResponse>(`/api/v1/prompt-templates/${id}/reset`)
  },
}
