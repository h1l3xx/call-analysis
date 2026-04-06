export interface PromptTemplateResponse {
  id: string
  name: string
  description: string | null
  content: string
  updatedAt: number
}

export interface SuggestResponse {
  suggestions: string[]
}
