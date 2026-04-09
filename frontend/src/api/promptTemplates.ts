import client from './client'
import type { PromptTemplateResponse, SuggestResponse, CreatePromptTemplateRequest } from '@/types'

export const promptTemplatesApi = {
  list() {
    return client.get<PromptTemplateResponse[]>('/api/v1/prompt-templates')
  },
  get(id: string) {
    return client.get<PromptTemplateResponse>(`/api/v1/prompt-templates/${id}`)
  },
  create(payload: CreatePromptTemplateRequest) {
    return client.post<PromptTemplateResponse>('/api/v1/prompt-templates', payload)
  },
  update(id: string, content: string) {
    return client.put<PromptTemplateResponse>(`/api/v1/prompt-templates/${id}`, { content })
  },
  reset(id: string) {
    return client.post<PromptTemplateResponse>(`/api/v1/prompt-templates/${id}/reset`)
  },
  suggest(id: string, description: string) {
    return client.post<SuggestResponse>(`/api/v1/prompt-templates/${id}/suggest`, { description })
  },
  remove(id: string) {
    return client.delete(`/api/v1/prompt-templates/${id}`)
  },
}
