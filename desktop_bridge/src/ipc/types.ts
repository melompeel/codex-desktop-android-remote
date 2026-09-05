export type IpcFrame = {
  type: "request" | "response" | "broadcast" | "client-discovery-request" | "client-discovery-response";
  requestId?: string;
  sourceClientId?: string;
  targetClientId?: string;
  targetClientIds?: string[];
  version?: number;
  method?: string;
  params?: unknown;
  request?: IpcFrame;
  response?: { canHandle?: boolean };
  resultType?: "success" | "error";
  handledByClientId?: string;
  result?: Record<string, unknown>;
  error?: string;
};

export type IpcBroadcastListener = (frame: IpcFrame) => void;
