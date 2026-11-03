import { randomUUID } from "node:crypto";
import type { Browser, Frame, Page } from "puppeteer-core";

export interface Target {
  tabId: string;
  frameId: string;
}

export interface TargetParams {
  tabId?: string;
  frameId?: string;
}

export function targetText(target: Target): string {
  return `Target: tabId=${target.tabId} frameId=${target.frameId}`;
}

/** Resolves exact live targets without falling back when a selection disappears. */
export class BrowserTargets {
  selected: Target | null = null;
  private tabIds = new WeakMap<Page, string>();
  private frameIds = new WeakMap<Frame, string>();

  constructor(private getBrowser: () => Promise<Browser>) {}

  async tabId(page: Page): Promise<string> {
    let id = this.tabIds.get(page);
    if (!id) {
      const session = await page.createCDPSession();
      try {
        const { targetInfo } = await session.send("Target.getTargetInfo");
        id = targetInfo.targetId;
        this.tabIds.set(page, id);
      } finally {
        await session.detach();
      }
    }
    return id;
  }

  frameId(frame: Frame): string {
    let id = this.frameIds.get(frame);
    if (!id) {
      // Frame has no public CDP ID. Opaque handles avoid private Puppeteer fields
      // and cannot accidentally bind to a replacement iframe at the same URL.
      id = `frame:${randomUUID()}`;
      this.frameIds.set(frame, id);
    }
    return id;
  }

  private choose(page: Page, tabId: string, frame: Frame) {
    const target = { tabId, frameId: this.frameId(frame) };
    this.selected = target;
    return { page, frame, target };
  }

  async resolve(params: TargetParams) {
    const browser = await this.getBrowser();
    const pages = (await browser.pages()).filter(page => !page.isClosed());
    const tabId = params.tabId ?? this.selected?.tabId;
    let page: Page | undefined;
    if (tabId !== undefined) {
      for (const candidate of pages) {
        if (await this.tabId(candidate) === tabId) {
          page = candidate;
          break;
        }
      }
      if (!page) throw new Error(`Tab target is closed or missing: ${tabId}. Choose a tabId with browser_tabs or open a new tab.`);
    } else {
      if (pages.length !== 1) {
        throw new Error("No tab selected. Use browser_tabs and supply a tabId, or browser_nav with newTab: true.");
      }
      page = pages[0];
    }

    // An explicit tabId resets to its main frame unless a frameId is supplied.
    const frameId = params.frameId ?? (params.tabId === undefined ? this.selected?.frameId : undefined);
    const frame = frameId === undefined ? page.mainFrame() : page.frames().find(
      candidate => !candidate.detached && this.frameId(candidate) === frameId,
    );
    if (!frame || frame.detached) {
      throw new Error(`Frame target is detached or missing: ${frameId}. Enumerate this tab with browser_tabs({tabId}).`);
    }
    return this.choose(page, await this.tabId(page), frame);
  }

  async open(params: TargetParams) {
    if (params.tabId !== undefined || params.frameId !== undefined) {
      throw new Error("newTab cannot be combined with tabId or frameId.");
    }
    const page = await (await this.getBrowser()).newPage();
    return this.choose(page, await this.tabId(page), page.mainFrame());
  }

  frames(page: Page) {
    return page.frames().filter(frame => !frame.detached).map(frame => ({
      frameId: this.frameId(frame),
      parentFrameId: frame.parentFrame() ? this.frameId(frame.parentFrame()!) : null,
      url: frame.url(),
      main: frame === page.mainFrame(),
    }));
  }
}
