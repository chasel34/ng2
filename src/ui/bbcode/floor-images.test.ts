import { describe, expect, it } from 'vitest';

import type { FloorAttachment } from '@/core/api';
import { parseBBCode } from '@/core/bbcode';

import { collectFloorImages } from './floor-images';

const BASE = 'https://img.nga.cn/attachments';

/** 2026-08-07 12:00 (UTC+8),补 [noimg] 日期目录用。 */
const POSTED_AT = 1786075200;

describe('collectFloorImages', () => {
  it('正文 [img] 相对路径拼上附件基址,并配缩略图变体', () => {
    const images = collectFloorImages(
      parseBBCode('看图[img]./mon_202608/07/-abc-1.jpg[/img]'),
      [],
      { base: BASE },
    );
    expect(images).toEqual([
      {
        url: `${BASE}/mon_202608/07/-abc-1.jpg`,
        thumbnailUrl: `${BASE}/mon_202608/07/-abc-1.jpg.thumb.jpg`,
      },
    ]);
  });

  it('嵌在引用块与行内标签里的图也收(和渲染器同一副视角)', () => {
    const images = collectFloorImages(
      parseBBCode('[quote][img]./mon_202608/07/a.jpg[/img][/quote][b][img]./mon_202608/07/b.jpg[/img][/b]'),
      [],
      { base: BASE },
    );
    expect(images.map((image) => image.url)).toEqual([
      `${BASE}/mon_202608/07/a.jpg`,
      `${BASE}/mon_202608/07/b.jpg`,
    ]);
  });

  it('[noimg] 缺日期目录时按发帖时间补(与 attachmentUrl 同一条规则)', () => {
    const images = collectFloorImages(parseBBCode('[noimg]./-7Qd36d-x.jpg[/noimg]'), [], {
      base: BASE,
      postedAt: POSTED_AT,
    });
    expect(images[0]?.url).toBe(`${BASE}/mon_202608/07/-7Qd36d-x.jpg`);
  });

  it('站外图片原样收进来,不配缩略图(图床没有 .thumb 约定)', () => {
    const images = collectFloorImages(
      parseBBCode('[img]https://i.example.com/pic.png[/img]'),
      [],
      { base: BASE },
    );
    expect(images).toEqual([{ url: 'https://i.example.com/pic.png' }]);
  });

  it('[album] 里的裸地址逐张展开', () => {
    const images = collectFloorImages(
      parseBBCode('[album]./mon_202608/07/a.jpg ./mon_202608/07/b.jpg[/album]'),
      [],
      { base: BASE },
    );
    expect(images.map((image) => image.url)).toEqual([
      `${BASE}/mon_202608/07/a.jpg`,
      `${BASE}/mon_202608/07/b.jpg`,
    ]);
  });

  it('图片附件排在正文之后,带服务端给的缩略图;非图片附件不收', () => {
    const attachments: FloorAttachment[] = [
      {
        url: `${BASE}/mon_202608/07/att.jpg`,
        thumbnailUrl: `${BASE}/mon_202608/07/att.jpg.thumb.jpg`,
        kind: 'img',
      },
      { url: `${BASE}/mon_202608/07/pack.zip`, kind: 'zip' },
    ];
    const images = collectFloorImages(
      parseBBCode('[img]./mon_202608/07/body.jpg[/img]'),
      attachments,
      { base: BASE },
    );
    expect(images.map((image) => image.url)).toEqual([
      `${BASE}/mon_202608/07/body.jpg`,
      `${BASE}/mon_202608/07/att.jpg`,
    ]);
    expect(images[1]?.thumbnailUrl).toBe(`${BASE}/mon_202608/07/att.jpg.thumb.jpg`);
  });

  it('同一张图正文与附件都出现时按第一次出现去重', () => {
    const attachments: FloorAttachment[] = [
      { url: `${BASE}/mon_202608/07/dup.jpg`, kind: 'img' },
    ];
    const images = collectFloorImages(
      parseBBCode('[img]./mon_202608/07/dup.jpg[/img]'),
      attachments,
      { base: BASE },
    );
    expect(images).toHaveLength(1);
  });

  it('没有图时给空列表', () => {
    expect(collectFloorImages(parseBBCode('纯文字'), [], { base: BASE })).toEqual([]);
  });
});
