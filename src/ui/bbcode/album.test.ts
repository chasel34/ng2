import { describe, expect, it } from 'vitest';

import { albumImageUrls } from './album';

/** 取法照 NGA 官方 `js_bbscode_core.js` 的 `[album]` 分支。 */
const options = { base: 'https://img.nga.cn/attachments' };

describe('albumImageUrls', () => {
  it('[img] 写法：取标签之间的地址，相对路径拼附件域名', () => {
    expect(
      albumImageUrls('[img]./mon_202608/07/a.jpg[/img][img]./mon_202608/07/b.jpg[/img]', options),
    ).toEqual([
      'https://img.nga.cn/attachments/mon_202608/07/a.jpg',
      'https://img.nga.cn/attachments/mon_202608/07/b.jpg',
    ]);
  });

  it('[url] 写法与绝对地址一样认', () => {
    expect(albumImageUrls('[url]https://example.test/a.png[/url]', options)).toEqual([
      'https://example.test/a.png',
    ]);
  });

  it('裸地址堆在一起时退回扫地址', () => {
    expect(
      albumImageUrls('./mon_202608/07/a.jpg ./mon_202608/07/b.jpg https://example.test/c.png', options),
    ).toEqual([
      'https://img.nga.cn/attachments/mon_202608/07/a.jpg',
      'https://img.nga.cn/attachments/mon_202608/07/b.jpg',
      'https://example.test/c.png',
    ]);
  });

  it('相对路径没带日期目录时按发帖时间补前缀', () => {
    expect(
      albumImageUrls('[img]./-7Qd36d-x.jpg[/img]', { ...options, postedAt: 1748252025 }),
    ).toEqual(['https://img.nga.cn/attachments/mon_202505/26/-7Qd36d-x.jpg']);
  });

  it('一个地址都没有时给空数组，而不是抛异常', () => {
    expect(albumImageUrls('', options)).toEqual([]);
    expect(albumImageUrls('这里什么都没有', options)).toEqual([]);
  });
});
