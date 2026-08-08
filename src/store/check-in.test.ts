import { beforeEach, describe, expect, it, vi } from 'vitest';

import { EMPTY_CHECK_IN_DAYS, beijingDayKey } from '@/core/local';

import { useCheckIn } from './check-in';

// 这个 store 的两个外部依赖都带 RN 原生实现（网络、MMKV），单测里换成假的：
// 要验的是「该不该发请求」，不是请求怎么发（那在 core/api/check-in.test.ts）
const checkInApi = vi.hoisted(() => vi.fn());
vi.mock('@/core/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/core/api')>()),
  checkIn: checkInApi,
}));
vi.mock('./nga-client', () => ({ fetchNga: vi.fn() }));
vi.mock('./storage', () => ({ storage: { getString: () => undefined, set: () => {} } }));

/** 一次不会立刻结束的签到请求，用来观察在途状态。 */
function deferred() {
  let resolve!: (value: { alreadyCheckedIn: boolean }) => void;
  const promise = new Promise<{ alreadyCheckedIn: boolean }>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe('useCheckIn.checkIn（去重与重复点击）', () => {
  beforeEach(() => {
    checkInApi.mockReset();
    useCheckIn.setState({ days: EMPTY_CHECK_IN_DAYS, pendingUid: null });
  });

  it('第一次签到发请求，成功后记下今天', async () => {
    checkInApi.mockResolvedValue({ alreadyCheckedIn: false, message: '签到成功' });

    const outcome = await useCheckIn.getState().checkIn('42');

    expect(outcome).toEqual({
      kind: 'checked-in',
      result: { alreadyCheckedIn: false, message: '签到成功' },
    });
    expect(checkInApi).toHaveBeenCalledTimes(1);
    expect(useCheckIn.getState().days['42']).toBe(beijingDayKey(Date.now()));
  });

  it('今天签过之后再点，压根不发请求', async () => {
    checkInApi.mockResolvedValue({ alreadyCheckedIn: false });
    await useCheckIn.getState().checkIn('42');

    await expect(useCheckIn.getState().checkIn('42')).resolves.toEqual({ kind: 'already-today' });
    expect(checkInApi).toHaveBeenCalledTimes(1);
  });

  it('上一次还在途时的重复点击不再发第二个请求', async () => {
    const pending = deferred();
    checkInApi.mockReturnValue(pending.promise);

    const first = useCheckIn.getState().checkIn('42');
    await expect(useCheckIn.getState().checkIn('42')).resolves.toEqual({ kind: 'in-flight' });

    pending.resolve({ alreadyCheckedIn: false });
    await first;
    expect(checkInApi).toHaveBeenCalledTimes(1);
  });

  it('服务端说「今天已经签到」也照样记下，今天不再问第二遍', async () => {
    checkInApi.mockResolvedValue({ alreadyCheckedIn: true, message: '你今天已经签到过了' });

    const outcome = await useCheckIn.getState().checkIn('42');

    expect(outcome).toMatchObject({ kind: 'checked-in', result: { alreadyCheckedIn: true } });
    await expect(useCheckIn.getState().checkIn('42')).resolves.toEqual({ kind: 'already-today' });
    expect(checkInApi).toHaveBeenCalledTimes(1);
  });

  it('签到失败不记账：错误抛给调用方，下次点击还会再试', async () => {
    checkInApi.mockRejectedValueOnce(new Error('你必须先登录论坛'));
    checkInApi.mockResolvedValueOnce({ alreadyCheckedIn: false });

    await expect(useCheckIn.getState().checkIn('42')).rejects.toThrow('你必须先登录论坛');
    expect(useCheckIn.getState().days['42']).toBeUndefined();

    await expect(useCheckIn.getState().checkIn('42')).resolves.toMatchObject({
      kind: 'checked-in',
    });
    expect(checkInApi).toHaveBeenCalledTimes(2);
  });

  it('一个账号签了不影响另一个账号', async () => {
    checkInApi.mockResolvedValue({ alreadyCheckedIn: false });
    await useCheckIn.getState().checkIn('42');

    await expect(useCheckIn.getState().checkIn('43')).resolves.toMatchObject({
      kind: 'checked-in',
    });
    expect(checkInApi).toHaveBeenCalledTimes(2);
  });
});
