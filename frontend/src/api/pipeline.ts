import client from './client'
import type { PipelineHealthResponse, PipelineAnalysesListResponse } from '@/types'

export const pipelineApi = {
  health() {
    return client.get<PipelineHealthResponse>('/api/v1/pipeline/health')
  },
  listAnalyses(params: { limit?: number; offset?: number; query?: string; hasQuality?: boolean } = {}) {
    return client.get<PipelineAnalysesListResponse>('/api/v1/pipeline/analyses', { params })
  },
  getAnalysis(resultId: string) {
    return client.get(`/api/v1/pipeline/analyses/${resultId}`)
  },
}
