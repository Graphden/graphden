# 45. Run with a default or supply nil

## Goal

Run a function with an omitted field, pass nil for one run, then save and
remove a nil binding. The editor offers this exercise when your deployment
supports structured-input-run and your account can extend and run its
optional label template on a lesson branch.

## Try it

1. Open **Run with a default or supply nil** from the editor on **main**.
   Select `optionalinput.structured/optional-label` in the Explorer.
2. Choose **Extend** from the template's **⋯** menu. Enter
   `tutorial-optional-label`, keep the root namespace and click **Save**.
3. Open **Run**, leave label blank and click **Run**. The result is
   `text: automatic`.
4. In the same form, type `null` in **label** and run again. JSON uses
   `null` to represent nil. The result is `explicit nil`.
5. On the function card, open **Remaining inputs**. Choose **Bind** beside **label**,
   enter `null` and save. Run again. With no remaining inputs, the result is
   still `explicit nil`.
6. On the function card, open **Structured inputs**. Choose **Remove** beside label
   under **Bound inputs** and confirm. Run with label blank again to see
   `text: automatic`.
7. Click **Finish** and delete the lesson branch.

The default applies when label is omitted. A Run value applies only to that
run, so the field remains open for binding. A saved binding supplies its own
value on later runs, even when the value is nil.
