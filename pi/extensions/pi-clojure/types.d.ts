// SPDX-License-Identifier: EPL-2.0

/** Runtime dependencies without published TypeScript declarations. */
declare module "parinfer" {
  interface ParinferOptions {
    cursorX?: number;
    cursorLine?: number;
    selectionStartLine?: number;
    forceBalance?: boolean;
    returnParens?: boolean;
  }

  interface ParinferResult {
    text: string | undefined;
    cursorX?: number;
    cursorLine?: number;
    tabStops?: Array<{ x: number; lineNo: number }>;
    previewCursorLine?: number;
    changed?: boolean;
    error?: { message: string; location: { x: number; lineNo: number } };
  }

  const parinfer: {
    indentMode(text: string, options?: ParinferOptions): ParinferResult;
  };
  export default parinfer;
}

declare module "bencode" {
  const bencode: {
    encode: (obj: unknown) => Buffer;
    decode: (data: Buffer | Uint8Array) => unknown;
  };
  export default bencode;
}
