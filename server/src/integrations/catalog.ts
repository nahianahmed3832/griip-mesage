import type { CapabilityDef, ConnectorDef } from "./types.js";

/**
 * The connector registry (docs/INTEGRATIONS.md › Catalog). Each implemented connector lists only what its code does,
 * with the exact provider scopes each capability needs; "coming soon" entries list no capabilities at all, so the
 * directory never advertises something Meldiq can't do. Icons are names the app maps to its own glyphs: no provider
 * logos or colours are copied.
 */

const G = "https://www.googleapis.com/auth/";
const GOOGLE_IDENTITY = ["openid", "email"];
const MS_BASE = ["offline_access", "User.Read"];

const cap = (c: CapabilityDef): CapabilityDef => c;

const GOOGLE_SETUP = [
  "In Google Cloud Console, create an OAuth client of type \"Web application\" for the Meldiq server.",
  "Add the authorised redirect URI <INTEGRATIONS_PUBLIC_URL>/v1/integrations/oauth/callback.",
  "On the OAuth consent screen, add only the scopes listed for the capabilities you enable.",
  "Set GOOGLE_OAUTH_CLIENT_ID and GOOGLE_OAUTH_CLIENT_SECRET in the server's environment (never in Git or the app).",
];
const MICROSOFT_SETUP = [
  "In Microsoft Entra admin center › App registrations, register a web app for the Meldiq server.",
  "Add the redirect URI <INTEGRATIONS_PUBLIC_URL>/v1/integrations/oauth/callback (platform: Web).",
  "Under API permissions add only the delegated Microsoft Graph permissions listed for the capabilities you enable.",
  "Create a client secret and set MICROSOFT_OAUTH_CLIENT_ID, MICROSOFT_OAUTH_CLIENT_SECRET and, if needed, MICROSOFT_OAUTH_TENANT.",
];

export const CONNECTORS: readonly ConnectorDef[] = [
  {
    id: "google-drive",
    provider: "google",
    name: "Google Drive",
    publisher: "Google",
    category: "files",
    icon: "cloud_folder",
    summary: "Find Drive files, Docs and Sheets and share links to them in conversations.",
    description:
      "Search and browse the Drive files you can already open, including Google Docs, Sheets and Slides, and share a link card in a conversation. Files stay in Google Drive: people open them there, with the access Google gives them.",
    featured: true,
    availability: "available",
    version: 1,
    capabilities: [
      cap({ key: "files.search", label: "Search your files", description: "Search the names of Drive files you can access.", kind: "read", accountType: "member", scopes: [...GOOGLE_IDENTITY, `${G}drive.metadata.readonly`], default: true }),
      cap({ key: "files.browse", label: "Browse folders", description: "Open your folders and shared drives.", kind: "read", accountType: "member", scopes: [...GOOGLE_IDENTITY, `${G}drive.metadata.readonly`], default: true }),
      cap({ key: "files.link", label: "Share file links", description: "Put a link card (name, type, link) in a conversation. The file itself isn't copied.", kind: "read", accountType: "member", scopes: [...GOOGLE_IDENTITY, `${G}drive.metadata.readonly`], default: true }),
      cap({ key: "files.unfurl", label: "Preview shared links", description: "Show details of Drive links others share, using your own access.", kind: "read", accountType: "member", scopes: [...GOOGLE_IDENTITY, `${G}drive.metadata.readonly`], default: true }),
      cap({ key: "sheets.inspect", label: "Spreadsheet tabs", description: "List a Google Sheet's tabs and their sizes (no cell contents).", kind: "read", accountType: "member", scopes: [...GOOGLE_IDENTITY, `${G}spreadsheets.readonly`], default: false }),
    ],
    dataAccess: [
      "Meldiq's server asks Google for file names, types, owners and links only when you search, browse or open a preview.",
      "Results go to you only. Nothing about your files is stored on Meldiq's server, except what you choose to share in a message (end-to-end encrypted).",
      "Your Google sign-in token is stored encrypted on Meldiq's server and is only used for your own requests.",
    ],
    setup: GOOGLE_SETUP,
    sensitivity: "standard",
    reviewNote:
      "drive.metadata.readonly is a restricted scope and spreadsheets.readonly a sensitive one: before a public launch Google requires app verification and, for restricted scopes, a security assessment. In Testing mode it works for up to 100 listed test users.",
    docsUrl: "https://developers.google.com/workspace/drive/api/guides/api-specific-auth",
  },
  {
    id: "google-calendar",
    provider: "google",
    name: "Google Calendar",
    publisher: "Google",
    category: "calendar",
    icon: "calendar",
    summary: "See your upcoming events and share them in conversations.",
    description:
      "Look up the events on your calendars and share an event's title, time and link in a conversation. If an admin allows it, you can also add an event to your calendar, only when you confirm it. Meldiq never changes or deletes existing events.",
    featured: true,
    availability: "available",
    version: 1,
    capabilities: [
      cap({ key: "events.read", label: "Your upcoming events", description: "List and search events on your calendars.", kind: "read", accountType: "member", scopes: [...GOOGLE_IDENTITY, `${G}calendar.events.readonly`], default: true }),
      cap({ key: "events.share", label: "Share events", description: "Put an event card (title, time, link) in a conversation.", kind: "read", accountType: "member", scopes: [...GOOGLE_IDENTITY, `${G}calendar.events.readonly`], default: true }),
      cap({
        key: "events.create",
        label: "Create events",
        description: "Add an event to your primary calendar, only when you confirm its title and time in Meldiq.",
        kind: "write",
        accountType: "member",
        scopes: [...GOOGLE_IDENTITY, `${G}calendar.events`],
        default: false,
      }),
    ],
    dataAccess: [
      "Event titles, times, locations and links are read from Google only when you look, and shown only to you.",
      "Nothing is stored on Meldiq's server except what you choose to share in a message.",
    ],
    setup: GOOGLE_SETUP,
    sensitivity: "standard",
    reviewNote: "calendar.events.readonly is a sensitive scope: Google app verification is required before a public launch.",
    docsUrl: "https://developers.google.com/workspace/calendar/api/auth",
  },
  {
    id: "microsoft-onedrive",
    provider: "microsoft",
    name: "OneDrive and Excel",
    publisher: "Microsoft",
    category: "files",
    icon: "cloud_files",
    summary: "Find OneDrive files, share links and see an Excel workbook's sheets and tables.",
    description:
      "Search and browse your OneDrive (work or school account) and share link cards. For Excel workbooks (.xlsx) stored in OneDrive for Business or SharePoint, see the worksheets and tables. Workbooks stay in Microsoft 365; Meldiq doesn't edit them and can't open local Excel files.",
    featured: true,
    availability: "available",
    version: 1,
    capabilities: [
      cap({ key: "files.search", label: "Search your files", description: "Search your OneDrive.", kind: "read", accountType: "member", scopes: [...MS_BASE, "Files.Read"], default: true }),
      cap({ key: "files.browse", label: "Browse folders", description: "Open your OneDrive folders.", kind: "read", accountType: "member", scopes: [...MS_BASE, "Files.Read"], default: true }),
      cap({ key: "files.link", label: "Share file links", description: "Put a link card in a conversation. The file itself isn't copied.", kind: "read", accountType: "member", scopes: [...MS_BASE, "Files.Read"], default: true }),
      cap({ key: "files.unfurl", label: "Preview shared links", description: "Show details of OneDrive links others share, using your own access.", kind: "read", accountType: "member", scopes: [...MS_BASE, "Files.Read"], default: true }),
      cap({ key: "workbook.inspect", label: "Excel sheets and tables", description: "List an .xlsx workbook's worksheets and tables (no cell contents). Business accounts only.", kind: "read", accountType: "member", scopes: [...MS_BASE, "Files.Read"], default: true }),
    ],
    dataAccess: [
      "File names, types, sizes and links are read from Microsoft Graph only when you search, browse or open a preview, and shown only to you.",
      "Workbook sheet and table names are read only when you ask. Cell contents are never read.",
      "Your Microsoft token is stored encrypted on Meldiq's server and only used for your own requests.",
    ],
    setup: MICROSOFT_SETUP,
    sensitivity: "standard",
    reviewNote: "For use outside your own tenant, Microsoft recommends publisher verification. Files.Read needs no admin consent.",
    docsUrl: "https://learn.microsoft.com/en-us/graph/onedrive-concept-overview",
  },
  {
    id: "microsoft-sharepoint",
    provider: "microsoft",
    name: "SharePoint",
    publisher: "Microsoft",
    category: "docs",
    icon: "sites",
    summary: "Find SharePoint sites and documents you can access and share links to them.",
    description:
      "Search the SharePoint sites you can access, browse their document libraries and share link cards. Microsoft decides what each person can open; Meldiq only shows what your own account can see.",
    featured: false,
    availability: "available",
    version: 1,
    capabilities: [
      cap({ key: "sites.search", label: "Find sites", description: "Search the SharePoint sites you can access.", kind: "read", accountType: "member", scopes: [...MS_BASE, "Sites.Read.All"], default: true }),
      cap({ key: "libraries.browse", label: "Browse libraries", description: "Open a site's document libraries and folders.", kind: "read", accountType: "member", scopes: [...MS_BASE, "Sites.Read.All", "Files.Read.All"], default: true }),
      cap({ key: "files.link", label: "Share file links", description: "Put a link card in a conversation.", kind: "read", accountType: "member", scopes: [...MS_BASE, "Files.Read.All"], default: true }),
      cap({ key: "workbook.inspect", label: "Excel sheets and tables", description: "List an .xlsx workbook's worksheets and tables (no cell contents).", kind: "read", accountType: "member", scopes: [...MS_BASE, "Files.Read.All"], default: true }),
    ],
    dataAccess: [
      "Site, library and file names and links are read only when you search or browse, and shown only to you.",
      "Only sites your own Microsoft account can access appear; Meldiq never lists unrelated sites.",
    ],
    setup: MICROSOFT_SETUP,
    sensitivity: "standard",
    reviewNote: "Sites.Read.All and Files.Read.All are delegated: they reach only what the signed-in person can already open. Some tenants require admin consent for them.",
    docsUrl: "https://learn.microsoft.com/en-us/graph/sharepoint-concept-overview",
  },
  {
    id: "microsoft-outlook-calendar",
    provider: "microsoft",
    name: "Outlook Calendar",
    publisher: "Microsoft",
    category: "calendar",
    icon: "calendar",
    summary: "See your upcoming Outlook events and share them in conversations.",
    description: "Look up the events on your Outlook calendar (work or school account) and share an event's title, time and link. Read-only: Meldiq can't create, change or delete events, and never reads mail.",
    featured: false,
    availability: "available",
    version: 1,
    capabilities: [
      cap({ key: "events.read", label: "Your upcoming events", description: "List and search events on your calendar.", kind: "read", accountType: "member", scopes: [...MS_BASE, "Calendars.Read"], default: true }),
      cap({ key: "events.share", label: "Share events", description: "Put an event card (title, time, link) in a conversation.", kind: "read", accountType: "member", scopes: [...MS_BASE, "Calendars.Read"], default: true }),
    ],
    dataAccess: ["Event titles, times, places and links are read from Microsoft Graph only when you look, and shown only to you."],
    setup: MICROSOFT_SETUP,
    sensitivity: "standard",
    reviewNote: "Calendars.Read is delegated and needs no admin consent; it reaches only the signed-in person's own calendars.",
    docsUrl: "https://learn.microsoft.com/en-us/graph/outlook-calendar-concept-overview",
  },
  {
    id: "github",
    provider: "github",
    name: "GitHub",
    publisher: "GitHub",
    category: "engineering",
    icon: "code",
    summary: "Pull request, issue, release and deployment updates in your channels, and issue search.",
    description:
      "An admin links the workspace to the Meldiq GitHub App installed on your GitHub organisation and picks which repositories notify which channels. Members can connect their own GitHub account to search issues and pull requests and preview links, with exactly the access GitHub gives them. Meldiq never stores source code.",
    featured: true,
    availability: "available",
    version: 1,
    capabilities: [
      cap({ key: "issues.search", label: "Search issues and pull requests", description: "Search the issues and pull requests you can see on GitHub.", kind: "read", accountType: "member", scopes: [], default: true }),
      cap({ key: "links.unfurl", label: "Preview shared links", description: "Show details of repository, issue and pull request links, using your own access.", kind: "read", accountType: "member", scopes: [], default: true }),
      cap({ key: "notify.channels", label: "Channel notifications", description: "Pull requests, issues, releases, workflow runs and deployments of chosen repositories, posted into chosen channels.", kind: "notify", accountType: "workspace", scopes: [], default: true }),
    ],
    dataAccess: [
      "For notifications, GitHub sends Meldiq's server event details for the repositories the app is installed on. Meldiq keeps only a title, a link and a short summary for each notification, for 30 days.",
      "Notifications are visible to the members of the channel they're posted in, and aren't end-to-end encrypted (the server received them from GitHub).",
      "Search and previews use your own GitHub token, stored encrypted; results go to you only.",
    ],
    setup: [
      "Create a GitHub App (Settings › Developer settings › GitHub Apps) with: Callback URL <INTEGRATIONS_PUBLIC_URL>/v1/integrations/oauth/callback, Webhook URL <INTEGRATIONS_PUBLIC_URL>/v1/integrations/webhooks/github and a random webhook secret.",
      "Repository permissions (read-only): Metadata, Issues, Pull requests, Actions, Deployments. Subscribe to events: Issues, Pull request, Release, Workflow run, Deployment status.",
      "Generate a client secret, then set GITHUB_APP_ID, GITHUB_APP_SLUG, GITHUB_APP_CLIENT_ID, GITHUB_APP_CLIENT_SECRET and GITHUB_APP_WEBHOOK_SECRET (each also as _FILE). No private key is needed: Meldiq never acts as the app itself.",
    ],
    sensitivity: "standard",
    reviewNote: null,
    docsUrl: "https://docs.github.com/en/apps/creating-github-apps",
  },
  {
    id: "jira",
    provider: "atlassian",
    name: "Jira",
    publisher: "Atlassian",
    category: "projects",
    icon: "tasks",
    summary: "Search Jira issues, preview links, and create issues or comments from Meldiq.",
    description:
      "Connect your own Atlassian account to search the Jira issues you can see, preview issue links and, when you choose to, create an issue from a message or add a comment. Jira permissions apply to everything. Channel notifications from Jira are planned.",
    featured: false,
    availability: "available",
    version: 1,
    capabilities: [
      cap({ key: "issues.search", label: "Search issues", description: "Search the Jira issues you can see.", kind: "read", accountType: "member", scopes: ["read:jira-work", "read:me", "offline_access"], default: true }),
      cap({ key: "links.unfurl", label: "Preview issue links", description: "Show an issue's summary and status, using your own access.", kind: "read", accountType: "member", scopes: ["read:jira-work", "read:me", "offline_access"], default: true }),
      cap({ key: "issues.create", label: "Create issues", description: "Create an issue from a message, only when you confirm it.", kind: "write", accountType: "member", scopes: ["read:jira-work", "write:jira-work", "read:me", "offline_access"], default: false }),
      cap({ key: "issues.comment", label: "Comment on issues", description: "Add a comment to an issue, only when you confirm it.", kind: "write", accountType: "member", scopes: ["read:jira-work", "write:jira-work", "read:me", "offline_access"], default: false }),
    ],
    dataAccess: [
      "Issue keys, summaries, statuses and links are read only when you search or preview, and shown only to you.",
      "Creating an issue or a comment sends exactly the text you confirm to Jira, as you.",
    ],
    setup: [
      "In the Atlassian developer console, create an OAuth 2.0 (3LO) app with callback <INTEGRATIONS_PUBLIC_URL>/v1/integrations/oauth/callback.",
      "Add Jira API scopes read:jira-work (and write:jira-work only if you enable creating issues or comments), plus User identity API read:me.",
      "Set ATLASSIAN_OAUTH_CLIENT_ID and ATLASSIAN_OAUTH_CLIENT_SECRET.",
    ],
    sensitivity: "standard",
    reviewNote: "Atlassian apps used by other organisations need to be distributed (sharing turned on) in the developer console.",
    docsUrl: "https://developer.atlassian.com/cloud/jira/platform/rest/v3/intro",
  },
  {
    id: "notion",
    provider: "notion",
    name: "Notion",
    publisher: "Notion",
    category: "docs",
    icon: "notes",
    summary: "Find Notion pages you've shared with Meldiq and share links to them.",
    description:
      "Connect Notion and choose which pages Meldiq may see (Notion asks you). Then search those pages and share link cards in conversations. Pages stay in Notion with their own sharing settings.",
    featured: false,
    availability: "available",
    version: 1,
    capabilities: [
      cap({ key: "pages.search", label: "Search pages", description: "Search the pages you shared with Meldiq.", kind: "read", accountType: "member", scopes: [], default: true }),
      cap({ key: "pages.link", label: "Share page links", description: "Put a link card in a conversation.", kind: "read", accountType: "member", scopes: [], default: true }),
      cap({ key: "links.unfurl", label: "Preview shared links", description: "Show a page's title, using your own access.", kind: "read", accountType: "member", scopes: [], default: true }),
    ],
    dataAccess: [
      "Notion lets you pick the pages Meldiq can see. Meldiq reads page titles and links only when you search or preview.",
      "Your Notion token is stored encrypted on Meldiq's server.",
    ],
    setup: [
      "Create a public integration at notion.so/profile/integrations with redirect URI <INTEGRATIONS_PUBLIC_URL>/v1/integrations/oauth/callback and the \"Read content\" capability only.",
      "Set NOTION_OAUTH_CLIENT_ID and NOTION_OAUTH_CLIENT_SECRET.",
    ],
    sensitivity: "standard",
    reviewNote: null,
    docsUrl: "https://developers.notion.com/",
  },
  {
    id: "linear",
    provider: "linear",
    name: "Linear",
    publisher: "Linear",
    category: "projects",
    icon: "tasks",
    summary: "Search Linear issues and preview issue links.",
    description: "Connect your Linear account to search the issues you can see and preview issue links. Creating issues and channel updates are planned.",
    featured: false,
    availability: "available",
    version: 1,
    capabilities: [
      cap({ key: "issues.search", label: "Search issues", description: "Search the Linear issues you can see.", kind: "read", accountType: "member", scopes: ["read"], default: true }),
      cap({ key: "links.unfurl", label: "Preview issue links", description: "Show an issue's title and state, using your own access.", kind: "read", accountType: "member", scopes: ["read"], default: true }),
    ],
    dataAccess: ["Issue identifiers, titles, states and links are read only when you search or preview, and shown only to you."],
    setup: [
      "Create an OAuth application in Linear (Settings › API) with callback <INTEGRATIONS_PUBLIC_URL>/v1/integrations/oauth/callback.",
      "Set LINEAR_OAUTH_CLIENT_ID and LINEAR_OAUTH_CLIENT_SECRET.",
    ],
    sensitivity: "standard",
    reviewNote: null,
    docsUrl: "https://linear.app/developers",
  },
  {
    id: "figma",
    provider: "figma",
    name: "Figma",
    publisher: "Figma",
    category: "docs",
    icon: "design",
    summary: "Preview Figma, FigJam and Slides links with your own access.",
    description:
      "Connect your Figma account and links to Figma Design, FigJam, Slides and prototypes show the file's name, its project, who last edited it and when, using your own Figma access. Only file metadata is read, never the design itself. Comment notifications in channels are planned.",
    featured: true,
    availability: "available",
    version: 1,
    capabilities: [
      cap({ key: "links.unfurl", label: "Preview Figma links", description: "Show a file's name, project and last edit, using your own access.", kind: "read", accountType: "member", scopes: ["current_user:read", "file_metadata:read"], default: true }),
    ],
    dataAccess: [
      "Meldiq reads a file's name, project, editor type and last-edit time only when a link is previewed, and shows them only to you.",
      "File contents, comments and version history are never requested. Your Figma token is stored encrypted on Meldiq's server.",
    ],
    setup: [
      "Create an OAuth app at figma.com/developers/apps with redirect URL <INTEGRATIONS_PUBLIC_URL>/v1/integrations/oauth/callback and the scopes current_user:read and file_metadata:read only.",
      "Set FIGMA_OAUTH_CLIENT_ID and FIGMA_OAUTH_CLIENT_SECRET.",
    ],
    sensitivity: "standard",
    reviewNote: "Public OAuth apps need Figma's review before people outside the app owner's organisation can connect.",
    docsUrl: "https://developers.figma.com/docs/rest-api/",
  },
  {
    id: "gitlab",
    provider: "gitlab",
    name: "GitLab",
    publisher: "GitLab",
    category: "engineering",
    icon: "code",
    summary: "Search GitLab issues and merge requests, and preview their links.",
    description:
      "Connect your GitLab account (gitlab.com or your organisation's instance) to search the open issues and merge requests you can see and preview their links, read-only and as you. Channel updates are planned; today a GitLab project webhook can post to a Meldiq incoming webhook.",
    featured: false,
    availability: "available",
    version: 1,
    capabilities: [
      cap({ key: "issues.search", label: "Search issues and merge requests", description: "Search the open issues and merge requests you can see.", kind: "read", accountType: "member", scopes: ["read_api", "read_user"], default: true }),
      cap({ key: "links.unfurl", label: "Preview GitLab links", description: "Show an issue's or merge request's title and state, using your own access.", kind: "read", accountType: "member", scopes: ["read_api", "read_user"], default: true }),
    ],
    dataAccess: [
      "Titles, states, authors and links of issues and merge requests are read only when you search or preview, and shown only to you.",
      "GitLab's narrowest read scope (read_api) covers more than Meldiq uses; Meldiq only calls the issue and merge request endpoints. Your token is stored encrypted on Meldiq's server.",
    ],
    setup: [
      "Create an application in GitLab (User or Group settings › Applications) with redirect URI <INTEGRATIONS_PUBLIC_URL>/v1/integrations/oauth/callback, confidential, scopes read_api and read_user.",
      "Set GITLAB_OAUTH_CLIENT_ID and GITLAB_OAUTH_CLIENT_SECRET, and GITLAB_BASE_URL for a self-managed instance (default https://gitlab.com).",
    ],
    sensitivity: "standard",
    reviewNote: null,
    docsUrl: "https://docs.gitlab.com/api/rest/",
  },
  {
    id: "incoming-webhooks",
    provider: "none",
    name: "Incoming webhooks",
    publisher: "Meldiq",
    category: "automation",
    icon: "webhook",
    summary: "Let your own tools post updates into a channel through a secret, signed URL.",
    description:
      "Create a webhook for a channel and give its URL and secret to a tool you run: a CI server (Jenkins, CircleCI), monitoring (Datadog, PagerDuty, Sentry alert rules), or an automation service (Zapier, Make). The tool sends a title, a short text and a link; Meldiq posts it in the channel. This is a generic webhook, not a native integration with those products.",
    featured: true,
    availability: "available",
    version: 1,
    capabilities: [
      cap({ key: "notify.channels", label: "Post to channels", description: "Title, short text and a link from your tool, posted into the webhook's channel.", kind: "notify", accountType: "workspace", scopes: [], default: true }),
    ],
    dataAccess: [
      "Only what your tool sends to the webhook: a title, up to 300 characters of text, a link and a name. Meldiq keeps each post for 30 days.",
      "Posts are visible to the channel's members and aren't end-to-end encrypted (they reach Meldiq's server in the clear).",
      "Each webhook has its own secret, stored encrypted. Anyone with the URL and secret can post, so treat them like a password.",
    ],
    setup: ["Needs INTEGRATIONS_PUBLIC_URL (the address tools post to) and INTEGRATIONS_TOKEN_KEY (seals each webhook's secret)."],
    sensitivity: "standard",
    reviewNote: null,
    docsUrl: "",
  },
  {
    id: "meldiq-sandbox",
    provider: "sandbox",
    name: "Meldiq Sandbox",
    publisher: "Meldiq (test only)",
    category: "automation",
    icon: "science",
    summary: "A test-only app that walks through every step of an integration with made-up data.",
    description:
      "For testing Meldiq itself: install, approval, connecting an account, searching, sharing links and channel notifications, all against a simulated provider on this server. Its files and events are made up. It never appears on production servers.",
    featured: false,
    availability: "sandbox",
    version: 1,
    capabilities: [
      cap({ key: "files.search", label: "Search sample files", description: "Search made-up files.", kind: "read", accountType: "member", scopes: ["sandbox.files.read"], default: true }),
      cap({ key: "files.browse", label: "Browse sample folders", description: "Open made-up folders.", kind: "read", accountType: "member", scopes: ["sandbox.files.read"], default: true }),
      cap({ key: "files.link", label: "Share sample links", description: "Put a link card in a conversation.", kind: "read", accountType: "member", scopes: ["sandbox.files.read"], default: true }),
      cap({ key: "files.unfurl", label: "Preview sample links", description: "Show details of sandbox links.", kind: "read", accountType: "member", scopes: ["sandbox.files.read"], default: true }),
      cap({ key: "notify.channels", label: "Sample notifications", description: "Signed test events posted into chosen channels.", kind: "notify", accountType: "workspace", scopes: ["sandbox.events"], default: true }),
    ],
    dataAccess: ["Everything is generated by this Meldiq server for testing. No third party is involved."],
    setup: ["Set INTEGRATIONS_SANDBOX=on on a development or test server (refused in production)."],
    sensitivity: "standard",
    reviewNote: null,
    docsUrl: "https://example.invalid/meldiq-sandbox",
  },
  // ───────────── Catalog-only: planned, with no capabilities claimed ─────────────
  ...comingSoon([
    ["gmail", "google", "Gmail", "Google", "communication", "mail", "Share an email's subject and link into a conversation, with narrowly scoped, read-only access you approve.", "high", "Gmail read scopes are restricted: Google verification and a security assessment are required before launch."],
    ["google-meet", "google", "Google Meet", "Google", "calendar", "video", "Meeting links from your calendar events.", "standard", null],
    ["outlook-mail", "microsoft", "Outlook mail", "Microsoft", "communication", "mail", "Share a message's subject and link, with narrowly scoped access you approve.", "high", "Mail.Read is a broad permission; it would need admin review and a clear consent design first."],
    ["microsoft-teams", "microsoft", "Microsoft Teams", "Microsoft", "communication", "video", "Teams meeting links and notifications (no second Teams client).", "standard", null],
    ["microsoft-planner", "microsoft", "Planner and To Do", "Microsoft", "projects", "tasks", "Tasks from messages and task updates in channels.", "standard", null],
    ["bitbucket", "none", "Bitbucket", "Atlassian", "engineering", "code", "Pull request and pipeline updates in channels.", "standard", null],
    ["sentry", "none", "Sentry", "Sentry", "engineering", "bug", "Error alerts in channels. Today: a Sentry alert rule can post to a Meldiq incoming webhook.", "standard", null],
    ["datadog", "none", "Datadog", "Datadog", "engineering", "bug", "Monitor alerts in channels. Today: a Datadog webhook can post to a Meldiq incoming webhook.", "standard", null],
    ["pagerduty", "none", "PagerDuty", "PagerDuty", "engineering", "bug", "Incident updates in channels. Today: a PagerDuty webhook can post to a Meldiq incoming webhook.", "standard", null],
    ["jenkins", "none", "Jenkins", "Jenkins", "engineering", "code", "Build results in channels. Today: a Jenkins job can post to a Meldiq incoming webhook.", "standard", null],
    ["circleci", "none", "CircleCI", "CircleCI", "engineering", "code", "Pipeline results in channels. Today: CircleCI webhooks can post to a Meldiq incoming webhook.", "standard", null],
    ["confluence", "atlassian", "Confluence", "Atlassian", "docs", "notes", "Find and link Confluence pages you can access.", "standard", null],
    ["trello", "none", "Trello", "Atlassian", "projects", "tasks", "Card links and board updates.", "standard", null],
    ["asana", "none", "Asana", "Asana", "projects", "tasks", "Task links and project updates.", "standard", null],
    ["monday", "none", "monday.com", "monday.com", "projects", "tasks", "Item links and board updates.", "standard", null],
    ["clickup", "none", "ClickUp", "ClickUp", "projects", "tasks", "Task links and status updates.", "standard", null],
    ["coda", "none", "Coda", "Coda", "docs", "notes", "Doc links and previews.", "standard", null],
    ["airtable", "none", "Airtable", "Airtable", "docs", "docs", "Base and record links.", "standard", null],
    ["canva", "none", "Canva", "Canva", "docs", "docs", "Design links and previews.", "standard", null],
    ["miro", "none", "Miro", "Miro", "docs", "docs", "Board links and previews.", "standard", null],
    ["dropbox", "none", "Dropbox", "Dropbox", "files", "cloud_files", "File links from your Dropbox.", "standard", null],
    ["box", "none", "Box", "Box", "files", "cloud_files", "File links from your Box.", "standard", null],
    ["zoom", "none", "Zoom", "Zoom", "calendar", "video", "Meeting links.", "standard", null],
    ["salesforce", "none", "Salesforce", "Salesforce", "crm", "people", "Record links and updates.", "standard", null],
    ["hubspot", "none", "HubSpot", "HubSpot", "crm", "people", "Contact and deal links.", "standard", null],
    ["zendesk", "none", "Zendesk", "Zendesk", "crm", "support", "Ticket links and updates.", "standard", null],
    ["intercom", "none", "Intercom", "Intercom", "crm", "support", "Conversation links and updates.", "standard", null],
    ["zapier", "none", "Zapier", "Zapier", "automation", "apps", "A native Zapier app. Today: a Zap's webhook step can post to a Meldiq incoming webhook.", "standard", null],
    ["make", "none", "Make", "Make", "automation", "apps", "A native Make app. Today: a scenario's HTTP module can post to a Meldiq incoming webhook.", "standard", null],
    [
      "ai-assistants",
      "none",
      "AI assistants",
      "OpenAI, Anthropic and others",
      "automation",
      "science",
      "Summaries and drafting with an assistant your organisation approves. Messages are end-to-end encrypted, so nothing would ever be sent to an AI service without an explicit, per-conversation choice.",
      "high",
      "Needs an approved product design and organisation controls before any message content leaves a device.",
    ],
  ]),
];

type Soon = [id: string, provider: ConnectorDef["provider"], name: string, publisher: string, category: ConnectorDef["category"], icon: string, plan: string, sensitivity: ConnectorDef["sensitivity"], review: string | null];

function comingSoon(list: Soon[]): ConnectorDef[] {
  return list.map(([id, provider, name, publisher, category, icon, plan, sensitivity, reviewNote]) => ({
    id,
    provider,
    name,
    publisher,
    category,
    icon,
    summary: `Planned: ${plan}`,
    description: `${name} isn't available in Meldiq yet. Planned: ${plan} Nothing is connected and no data is requested.`,
    featured: false,
    availability: "coming_soon",
    version: 0,
    capabilities: [],
    dataAccess: [],
    setup: [],
    sensitivity,
    reviewNote,
    docsUrl: "",
  }));
}

const BY_ID = new Map(CONNECTORS.map((c) => [c.id, c]));

export function connectorDef(id: string): ConnectorDef | undefined {
  return BY_ID.get(id);
}

/** The capabilities an install asks for when the admin doesn't choose: each connector's least-privilege defaults. */
export function defaultCapabilities(def: ConnectorDef): string[] {
  return def.capabilities.filter((c) => c.default).map((c) => c.key);
}

/** The scopes to request for [capabilities] (sorted, no duplicates). */
export function scopesFor(def: ConnectorDef, capabilities: Iterable<string>, accountType: "member" | "workspace"): string[] {
  const wanted = new Set(capabilities);
  const scopes = new Set<string>();
  for (const c of def.capabilities) if (wanted.has(c.key) && c.accountType === accountType) for (const s of c.scopes) scopes.add(s);
  return [...scopes].sort();
}
