import { zodResolver } from '@hookform/resolvers/zod';
import * as Dialog from '@radix-ui/react-dialog';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AnimatePresence, motion } from 'motion/react';
import { Check, ChevronLeft, ChevronRight, X } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import {
  api,
  ApiError,
  type CreateTaskInput,
  type SourceResolution,
  type SourceType,
  type TaskDetail,
} from '../api';
import { modeLabels, sourceLabels } from '../lib/format';

const createTaskSchema = z.object({
  kind: z.enum(['CONTENT_COMMENTS', 'CREATOR_WATCH']),
  sourceType: z.enum(['VIDEO', 'DYNAMIC', 'CREATOR']),
  sourceInput: z.string().trim().min(3, '请输入有效的来源标识或链接'),
  name: z.string().trim().min(1, '请输入任务名称').max(64, '任务名称不能超过 64 个字符'),
  collectionMode: z.enum(['FOLLOW_ONLY', 'BACKFILL_ONLY']),
  credentialProfileId: z.string().min(1, '请选择访问凭据'),
  scheduleStrategy: z.enum(['ADAPTIVE', 'CRON']),
  cronExpression: z.string(),
  watchVideo: z.boolean(),
  watchDynamic: z.boolean(),
  remark: z.string().max(500, '备注不能超过 500 个字符'),
}).superRefine((values, context) => {
  if (values.kind === 'CREATOR_WATCH' && !values.watchVideo && !values.watchDynamic) {
    context.addIssue({ code: 'custom', path: ['watchVideo'], message: '至少选择视频或动态之一' });
  }
  if (values.scheduleStrategy === 'CRON' && !values.cronExpression.trim()) {
    context.addIssue({ code: 'custom', path: ['cronExpression'], message: '请输入 Cron 表达式' });
  }
});

type CreateTaskValues = z.infer<typeof createTaskSchema>;

interface CreateTaskDrawerProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated: (task: TaskDetail) => void;
  onExistingTask: (taskId: string) => void;
}

const stepFields: Array<Array<keyof CreateTaskValues>> = [
  ['kind', 'sourceType'],
  ['sourceInput'],
  [
    'name',
    'collectionMode',
    'credentialProfileId',
    'scheduleStrategy',
    'cronExpression',
    'watchVideo',
    'watchDynamic',
    'remark',
  ],
  [],
];

export function CreateTaskDrawer({
  open,
  onOpenChange,
  onCreated,
  onExistingTask,
}: CreateTaskDrawerProps) {
  const [step, setStep] = useState(0);
  const [resolvedSource, setResolvedSource] = useState<SourceResolution | null>(null);
  const queryClient = useQueryClient();
  const {
    register,
    watch,
    setValue,
    getValues,
    trigger,
    reset,
    handleSubmit,
    formState: { errors },
  } = useForm<CreateTaskValues>({
    resolver: zodResolver(createTaskSchema),
    defaultValues: {
      kind: 'CONTENT_COMMENTS',
      sourceType: 'VIDEO',
      sourceInput: '',
      name: '',
      collectionMode: 'FOLLOW_ONLY',
      credentialProfileId: '',
      scheduleStrategy: 'ADAPTIVE',
      cronExpression: '0 */5 * * * *',
      watchVideo: true,
      watchDynamic: true,
      remark: '',
    },
  });

  const kind = watch('kind');
  const sourceType = watch('sourceType');
  const collectionMode = watch('collectionMode');
  const scheduleStrategy = watch('scheduleStrategy');
  const credentials = useQuery({
    queryKey: ['credentials'],
    queryFn: api.getCredentials,
    enabled: open,
  });
  const capabilities = useQuery({
    queryKey: ['task-capabilities'],
    queryFn: api.getTaskCapabilities,
    enabled: open,
    staleTime: 5 * 60_000,
  });
  const resolution = useMutation({
    mutationFn: ({ type, input }: { type: SourceType; input: string }) =>
      api.resolveSource(type, input),
    onSuccess: (result) => {
      setResolvedSource(result);
      if (!getValues('name').trim()) {
        const prefix = result.source.type === 'CREATOR' ? '内容发现' : '评论采集';
        setValue('name', `${result.source.title} · ${prefix}`, { shouldValidate: true });
      }
    },
  });
  const creation = useMutation({
    mutationFn: ({ input, key }: { input: CreateTaskInput; key: string }) =>
      api.createTask(input, key),
    onSuccess: (task) => {
      void queryClient.invalidateQueries({ queryKey: ['tasks'] });
      setStep(0);
      setResolvedSource(null);
      reset();
      onOpenChange(false);
      onCreated(task);
    },
  });

  useEffect(() => {
    const firstCredential = credentials.data?.find((item) => item.enabled);
    if (firstCredential && !getValues('credentialProfileId')) {
      setValue('credentialProfileId', firstCredential.id);
    }
  }, [credentials.data, getValues, setValue]);

  useEffect(() => {
    if (kind === 'CREATOR_WATCH') {
      setValue('sourceType', 'CREATOR');
      setValue('collectionMode', 'FOLLOW_ONLY');
    } else if (getValues('sourceType') === 'CREATOR') {
      setValue('sourceType', 'VIDEO');
    }
    setResolvedSource(null);
  }, [getValues, kind, setValue]);

  const fullCollectionAvailable = useMemo(() => capabilities.data
    ?.find((item) => item.kind === kind)
    ?.collectionModes.includes('BACKFILL_ONLY') ?? false, [capabilities.data, kind]);

  const close = (): void => {
    setStep(0);
    setResolvedSource(null);
    creation.reset();
    resolution.reset();
    reset();
    onOpenChange(false);
  };

  const next = async (): Promise<void> => {
    const valid = await trigger(stepFields[step] ?? []);
    if (!valid) return;
    if (step === 1) {
      const result = await resolution.mutateAsync({
        type: getValues('sourceType'),
        input: getValues('sourceInput'),
      });
      if (!result.accessible) return;
    }
    setStep((current) => Math.min(current + 1, 3));
  };

  const create = (startNow: boolean) => handleSubmit(async (values) => {
    const input: CreateTaskInput = {
      name: values.name.trim(),
      kind: values.kind,
      source: { type: values.sourceType, input: values.sourceInput.trim() },
      collectionMode: values.collectionMode,
      credentialProfileId: values.credentialProfileId,
      schedule: {
        strategy: values.scheduleStrategy,
        cronExpression: values.scheduleStrategy === 'CRON' ? values.cronExpression.trim() : undefined,
        zoneId: 'Asia/Shanghai',
      },
      watchedContentTypes: values.kind === 'CREATOR_WATCH'
        ? [
            ...(values.watchVideo ? ['VIDEO' as const] : []),
            ...(values.watchDynamic ? ['DYNAMIC' as const] : []),
          ]
        : undefined,
      desiredState: startNow ? 'ACTIVE' : 'PAUSED',
      startNow,
      remark: values.remark.trim() || undefined,
    };
    await creation.mutateAsync({ input, key: crypto.randomUUID() });
  });

  const creationProblem = creation.error instanceof ApiError ? creation.error.problem : null;
  const existingTaskId = creationProblem?.existingTaskId;
  const resolutionError = resolution.error instanceof ApiError
    ? resolution.error.problem.detail
    : resolution.error
      ? '来源解析失败，请稍后重试'
      : null;

  return (
    <Dialog.Root open={open} onOpenChange={(nextOpen) => nextOpen ? onOpenChange(true) : close()}>
      <Dialog.Portal>
        <Dialog.Overlay className="drawer-overlay" />
        <Dialog.Content className="drawer-content" aria-describedby="create-task-description">
          <header className="drawer-header">
            <div>
              <Dialog.Title>新建任务</Dialog.Title>
              <Dialog.Description id="create-task-description">
                步骤 {step + 1} / 4
              </Dialog.Description>
            </div>
            <Dialog.Close asChild>
              <button type="button" className="icon-button" aria-label="关闭新建任务">
                <X size={18} aria-hidden="true" />
              </button>
            </Dialog.Close>
          </header>

          <div className="step-track" aria-label="创建进度">
            {[0, 1, 2, 3].map((item) => (
              <span key={item} className={item <= step ? 'is-complete' : undefined} />
            ))}
          </div>

          <form className="drawer-form" noValidate>
            <AnimatePresence mode="wait" initial={false}>
              <motion.div
                key={step}
                className="drawer-step"
                initial={{ opacity: 0, x: 8 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: -8 }}
                transition={{ duration: 0.14, ease: 'easeOut' }}
              >
                {step === 0 ? (
                  <fieldset className="choice-group">
                    <legend>选择任务类型</legend>
                    <label className="choice-row">
                      <input type="radio" value="CONTENT_COMMENTS" {...register('kind')} />
                      <span>
                        <strong>评论采集</strong>
                        <span>采集单个视频或动态的评论。</span>
                      </span>
                    </label>
                    <label className="choice-row">
                      <input type="radio" value="CREATOR_WATCH" {...register('kind')} />
                      <span>
                        <strong>UP 主内容监控</strong>
                        <span>发现新视频或动态，并建立评论任务。</span>
                      </span>
                    </label>

                    {kind === 'CONTENT_COMMENTS' ? (
                      <div className="field-group">
                        <span className="field-label">内容来源</span>
                        <div className="inline-options">
                          <label><input type="radio" value="VIDEO" {...register('sourceType')} /> 视频</label>
                          <label><input type="radio" value="DYNAMIC" {...register('sourceType')} /> 动态</label>
                        </div>
                      </div>
                    ) : (
                      <div className="field-note">来源固定为 UP 主 UID。</div>
                    )}
                  </fieldset>
                ) : null}

                {step === 1 ? (
                  <div className="form-stack">
                    <div className="step-heading">
                      <h2>填写{sourceLabels[sourceType]}来源</h2>
                      <p>支持规范 ID 和 Bilibili 页面链接。</p>
                    </div>
                    <label className="field">
                      <span>{sourceType === 'VIDEO' ? 'BV 号或视频链接' : sourceType === 'DYNAMIC' ? '动态 ID 或链接' : 'UID 或空间链接'}</span>
                      <input
                        autoFocus
                        placeholder={sourceType === 'VIDEO' ? '例如 BV1xx411c7mD' : '请输入数字 ID'}
                        {...register('sourceInput')}
                        onChange={(event) => {
                          void register('sourceInput').onChange(event);
                          setResolvedSource(null);
                        }}
                        aria-invalid={Boolean(errors.sourceInput)}
                      />
                      {errors.sourceInput ? <span className="field-error">{errors.sourceInput.message}</span> : null}
                    </label>
                    {resolution.isPending ? <div className="field-note">正在验证来源…</div> : null}
                    {resolutionError ? <div className="form-error" role="alert">{resolutionError}</div> : null}
                    {resolvedSource ? (
                      <div className="resolution-result">
                        <Check size={17} aria-hidden="true" />
                        <span>
                          <strong>{resolvedSource.source.title}</strong>
                          <span>{resolvedSource.source.id}</span>
                        </span>
                      </div>
                    ) : null}
                  </div>
                ) : null}

                {step === 2 ? (
                  <div className="form-stack">
                    <div className="step-heading">
                      <h2>任务配置</h2>
                      <p>持续增量采集和自适应调度为默认设置。</p>
                    </div>
                    <label className="field">
                      <span>任务名称</span>
                      <input {...register('name')} aria-invalid={Boolean(errors.name)} />
                      {errors.name ? <span className="field-error">{errors.name.message}</span> : null}
                    </label>

                    {kind === 'CONTENT_COMMENTS' ? (
                      <label className="field">
                        <span>采集模式</span>
                        <select {...register('collectionMode')}>
                          <option value="FOLLOW_ONLY">持续增量采集</option>
                          {fullCollectionAvailable ? <option value="BACKFILL_ONLY">单次历史回填</option> : null}
                        </select>
                      </label>
                    ) : (
                      <fieldset className="field-group">
                        <legend>发现内容</legend>
                        <div className="inline-options">
                          <label><input type="checkbox" {...register('watchVideo')} /> 视频</label>
                          <label><input type="checkbox" {...register('watchDynamic')} /> 动态</label>
                        </div>
                        {errors.watchVideo ? <span className="field-error">{errors.watchVideo.message}</span> : null}
                      </fieldset>
                    )}

                    <label className="field">
                      <span>访问凭据</span>
                      <select {...register('credentialProfileId')} aria-invalid={Boolean(errors.credentialProfileId)}>
                        <option value="">请选择</option>
                        {credentials.data?.map((credential) => (
                          <option key={credential.id} value={credential.id} disabled={!credential.enabled}>
                            {credential.name}{credential.valid ? '' : '（待验证）'}
                          </option>
                        ))}
                      </select>
                      {errors.credentialProfileId ? <span className="field-error">{errors.credentialProfileId.message}</span> : null}
                    </label>

                    {collectionMode === 'FOLLOW_ONLY' ? (
                      <fieldset className="field-group">
                        <legend>调度方式</legend>
                        <div className="inline-options">
                          <label><input type="radio" value="ADAPTIVE" {...register('scheduleStrategy')} /> 自适应</label>
                          <label><input type="radio" value="CRON" {...register('scheduleStrategy')} /> Cron</label>
                        </div>
                      </fieldset>
                    ) : null}

                    {scheduleStrategy === 'CRON' && collectionMode === 'FOLLOW_ONLY' ? (
                      <label className="field">
                        <span>Cron 表达式</span>
                        <input {...register('cronExpression')} aria-invalid={Boolean(errors.cronExpression)} />
                        <span className="field-help">使用六段 Spring Cron，时区为 Asia/Shanghai。</span>
                        {errors.cronExpression ? <span className="field-error">{errors.cronExpression.message}</span> : null}
                      </label>
                    ) : null}

                    <label className="field">
                      <span>备注（可选）</span>
                      <textarea rows={3} {...register('remark')} />
                      {errors.remark ? <span className="field-error">{errors.remark.message}</span> : null}
                    </label>
                  </div>
                ) : null}

                {step === 3 ? (
                  <div className="review-section">
                    <div className="step-heading">
                      <h2>确认任务</h2>
                      <p>创建后可在任务详情查看执行和评论。</p>
                    </div>
                    <dl className="detail-list">
                      <div><dt>名称</dt><dd>{getValues('name')}</dd></div>
                      <div><dt>来源</dt><dd>{sourceLabels[getValues('sourceType')]} · {resolvedSource?.source.id ?? getValues('sourceInput')}</dd></div>
                      <div><dt>模式</dt><dd>{modeLabels[getValues('collectionMode')]}</dd></div>
                      <div><dt>调度</dt><dd>{getValues('scheduleStrategy') === 'ADAPTIVE' ? '自适应频率' : getValues('cronExpression')}</dd></div>
                      <div><dt>访问凭据</dt><dd>{credentials.data?.find((item) => item.id === getValues('credentialProfileId'))?.name ?? '—'}</dd></div>
                    </dl>
                    {creationProblem ? (
                      <div className="form-error" role="alert">
                        <span>{creationProblem.detail}</span>
                        {existingTaskId ? (
                          <button
                            type="button"
                            className="text-button"
                            onClick={() => {
                              close();
                              onExistingTask(existingTaskId);
                            }}
                          >
                            查看已有任务
                          </button>
                        ) : null}
                      </div>
                    ) : null}
                  </div>
                ) : null}
              </motion.div>
            </AnimatePresence>
          </form>

          <footer className="drawer-footer">
            <button
              type="button"
              className="button button--secondary"
              disabled={step === 0 || creation.isPending}
              onClick={() => setStep((current) => Math.max(0, current - 1))}
            >
              <ChevronLeft size={16} aria-hidden="true" />
              上一步
            </button>
            <div className="drawer-footer__actions">
              {step < 3 ? (
                <button
                  type="button"
                  className="button button--primary"
                  onClick={() => { void next(); }}
                  disabled={resolution.isPending}
                >
                  下一步
                  <ChevronRight size={16} aria-hidden="true" />
                </button>
              ) : (
                <>
                  <button
                    type="button"
                    className="button button--secondary"
                    onClick={() => { void create(false)().catch(() => undefined); }}
                    disabled={creation.isPending}
                  >
                    创建但暂停
                  </button>
                  <button
                    type="button"
                    className="button button--primary"
                    onClick={() => { void create(true)().catch(() => undefined); }}
                    disabled={creation.isPending}
                  >
                    {creation.isPending ? '正在创建…' : '创建并执行'}
                  </button>
                </>
              )}
            </div>
          </footer>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
