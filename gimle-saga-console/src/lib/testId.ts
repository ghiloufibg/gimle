/** URL-safe encoding for testIds (which contain ":" and "#"). */
export const toTestParam = (testId: string) => testId.replace(/:/g, "__").replace(/#/g, "--");
export const fromTestParam = (param: string) => param.replace(/__/g, ":").replace(/--/g, "#");
